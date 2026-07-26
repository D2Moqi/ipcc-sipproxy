package cn.ipcc.sipproxy.websocket;

import cn.ipcc.sipproxy.autoconfigure.SipProxyProperties;
import cn.ipcc.sipproxy.core.SipProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * 僵尸 WebSocket 会话清理任务
 * <p>
 * 设计思路：每 60 秒扫描所有 WebSocket 会话，对最后活跃时间超过
 * {@code sipproxy.heartbeat.idle-timeout} 的会话主动关闭并清理 Redis 注册映射，
 * 避免向已断开的 sessionId 转发 SIP 消息。
 * <p>
 * 触发场景：
 * <ul>
 *   <li>客户端异常断开（网络抖动、浏览器崩溃），WebSocket onClose 未及时触发</li>
 *   <li>JsSIP 客户端停止心跳但 TCP 连接未关闭</li>
 * </ul>
 * <p>
 * 启用条件：{@code sipproxy.heartbeat.zombie-clean-enabled=true}（默认启用）。
 */
@Slf4j
public class ZombieSessionCleaner {

    /** 清理任务执行间隔：60 秒 */
    private static final long CLEAN_INTERVAL_MS = 60_000L;

    /** sipproxy 配置属性 */
    private final SipProxyProperties sipProxyProperties;

    /** WebSocket 会话管理器 */
    private final WsSessionManager wsSessionManager;

    /** sipproxy 核心服务，用于清理注册信息 */
    private final SipProxyService sipProxyService;

    /**
     * 构造僵尸会话清理任务
     *
     * @param sipProxyProperties sipproxy 配置属性
     * @param wsSessionManager   WebSocket 会话管理器
     * @param sipProxyService    sipproxy 核心服务
     */
    public ZombieSessionCleaner(SipProxyProperties sipProxyProperties,
                                WsSessionManager wsSessionManager,
                                SipProxyService sipProxyService) {
        this.sipProxyProperties = sipProxyProperties;
        this.wsSessionManager = wsSessionManager;
        this.sipProxyService = sipProxyService;
    }

    /**
     * 定时清理僵尸会话
     * <p>
     * 处理流程：
     * <ol>
     *   <li>遍历所有 WebSocket 会话</li>
     *   <li>对每个会话检查 lastActiveAt 与当前时间差</li>
     *   <li>差值 &gt; idleTimeout 的会话：主动 close + 清理 Redis 注册映射</li>
     * </ol>
     */
    @Scheduled(fixedDelay = CLEAN_INTERVAL_MS)
    public void cleanZombieSessions() {
        long now = System.currentTimeMillis();
        // 配置项单位为秒，转换为毫秒
        long idleTimeoutMs = sipProxyProperties.getHeartbeat().getIdleTimeout() * 1000L;

        for (WebSocketSession session : wsSessionManager.getAllSessions()) {
            Object lastActive = session.getAttributes().get("lastActiveAt");
            if (!(lastActive instanceof Long lastActiveAt)) {
                continue;
            }
            if (now - lastActiveAt > idleTimeoutMs) {
                log.warn("[cleanZombieSessions][清理僵尸会话] sessionId={}, idleSeconds={}",
                        session.getId(), (now - lastActiveAt) / 1000);
                try {
                    // 使用 POLICY_VIOLATION(1008)：表示连接违反策略（超时未活动）
                    session.close(CloseStatus.POLICY_VIOLATION);
                } catch (IOException e) {
                    log.error("[cleanZombieSessions][关闭会话失败] sessionId={}", session.getId(), e);
                }
                sipProxyService.cleanupRegisterInfo(session.getId());
            }
        }
    }
}
