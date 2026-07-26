package cn.ipcc.sipproxy.cluster;

import cn.ipcc.sipproxy.websocket.WsSessionManager;
import lombok.extern.slf4j.Slf4j;

/**
 * 集群广播消息消费者
 * <p>
 * 收到广播消息后，检查本实例是否持有目标 WebSocket 会话：
 * <ul>
 *   <li>持有：转发 SIP 消息到目标会话</li>
 *   <li>不持有：忽略（其他实例会处理）</li>
 * </ul>
 * <p>
 * 设计意图：作为 {@link WsMessageSender} 的接收方，解耦消息接收与处理逻辑，
 * 便于扩展（如增加监控、限流、告警等切面）。
 */
@Slf4j
public class ClusterBroadcastConsumer {

    /** WebSocket 消息广播发送器（注入 local / redis / MQ 之一） */
    private final WsMessageSender wsMessageSender;

    /** WebSocket 会话管理器 */
    private final WsSessionManager wsSessionManager;

    /**
     * 构造集群广播消费者
     *
     * @param wsMessageSender  WebSocket 消息广播发送器
     * @param wsSessionManager WebSocket 会话管理器
     */
    public ClusterBroadcastConsumer(WsMessageSender wsMessageSender,
                                    WsSessionManager wsSessionManager) {
        this.wsMessageSender = wsMessageSender;
        this.wsSessionManager = wsSessionManager;
    }

    /**
     * 初始化：注册接收回调
     * <p>
     * 由 Spring 容器在 Bean 初始化完成后调用，将 {@link #onBroadcast(SipWsBroadcastMessage)}
     * 注册为 {@link WsMessageSender} 的接收回调。
     */
    public void init() {
        wsMessageSender.onReceive(this::onBroadcast);
    }

    /**
     * 接收集群广播消息
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>按 {@code targetType} 分发（USER/SESSION/ALL）</li>
     *   <li>USER：通过 username:domain 查询本实例 WebSocket 会话，持有则转发</li>
     *   <li>SESSION：直接按 sessionId 查询并转发</li>
     *   <li>ALL：遍历所有会话转发</li>
     * </ol>
     *
     * @param message 广播消息载体
     */
    public void onBroadcast(SipWsBroadcastMessage message) {
        if (message.getTargetType() == null) {
            log.warn("[onBroadcast][targetType 为空] message={}", message);
            return;
        }
        switch (message.getTargetType()) {
            case "USER" -> handleUserBroadcast(message);
            case "SESSION" -> handleSessionBroadcast(message);
            case "ALL" -> handleAllBroadcast(message);
            default -> log.warn("[onBroadcast][未知 targetType] {}", message.getTargetType());
        }
    }

    /**
     * 处理 USER 类型广播
     * <p>
     * target 格式为 "username:domain"，查询本实例是否持有该坐席会话，
     * 持有则转发 SIP 消息。
     */
    private void handleUserBroadcast(SipWsBroadcastMessage message) {
        if (message.getTarget() == null) {
            log.warn("[handleUserBroadcast][target 为空] message={}", message);
            return;
        }
        String[] parts = message.getTarget().split(":");
        if (parts.length < 2) {
            log.warn("[handleUserBroadcast][target 格式非法,应为 username:domain] target={}", message.getTarget());
            return;
        }
        String sessionId = wsSessionManager.getSessionIdByUser(parts[0], parts[1]);
        if (sessionId != null) {
            wsSessionManager.send(sessionId, message.getMessage());
            log.info("[onBroadcast][USER] 已转发到本实例会话, target={}", message.getTarget());
        }
        // 不持有则忽略，其他实例会处理
    }

    /**
     * 处理 SESSION 类型广播
     * <p>
     * 直接按 sessionId 查询并转发，未命中说明目标会话不在本实例。
     */
    private void handleSessionBroadcast(SipWsBroadcastMessage message) {
        wsSessionManager.send(message.getTarget(), message.getMessage());
        log.info("[onBroadcast][SESSION] 已转发, sessionId={}", message.getTarget());
    }

    /**
     * 处理 ALL 类型广播
     * <p>
     * 遍历本实例所有会话转发，每个实例都会执行此操作实现全量广播。
     */
    private void handleAllBroadcast(SipWsBroadcastMessage message) {
        for (var session : wsSessionManager.getAllSessions()) {
            wsSessionManager.send(session.getId(), message.getMessage());
        }
        log.info("[onBroadcast][ALL] 已广播到所有会话");
    }
}
