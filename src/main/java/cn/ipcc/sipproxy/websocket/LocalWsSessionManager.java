package cn.ipcc.sipproxy.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 WebSocket 会话管理器默认实现
 * <p>
 * 单实例部署时使用，通过 {@link ConcurrentHashMap} 维护 sessionId → {@link WebSocketSession}
 * 与 username:domain → sessionId 双向映射。
 * <p>
 * 设计约束：
 * <ul>
 *   <li>会话存储与查询均为 O(1)，适合单实例 1k 量级坐席连接</li>
 *   <li>多实例部署需替换为 {@code RedisWsSessionManager} 或借助 {@code WsMessageSender} 广播</li>
 * </ul>
 */
@Slf4j
public class LocalWsSessionManager implements WsSessionManager {

    /** WebSocket 会话表：sessionId → WebSocketSession */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** 坐席身份映射表：username:domain → sessionId（REGISTER 成功后建立） */
    private final Map<String, String> userSessionMapping = new ConcurrentHashMap<>();

    @Override
    public void send(String sessionId, String messageContent) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            log.warn("[send][WebSocket 会话不存在或已关闭] sessionId={}", sessionId);
            return;
        }
        try {
            session.sendMessage(new TextMessage(messageContent));
        } catch (Exception e) {
            log.error("[send][发送 WebSocket 消息失败] sessionId={}", sessionId, e);
        }
    }

    @Override
    public String getSessionIdByUser(String username, String domain) {
        if (username == null || domain == null) {
            return null;
        }
        return userSessionMapping.get(buildUserKey(username, domain));
    }

    @Override
    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
        // 初始化最后活跃时间，供 ZombieSessionCleaner 判定
        session.getAttributes().put("lastActiveAt", System.currentTimeMillis());
        log.info("[register][WebSocket 会话已注册] sessionId={}", session.getId());
    }

    @Override
    public void unregister(String sessionId) {
        WebSocketSession removed = sessions.remove(sessionId);
        // 清理 userSessionMapping 中指向该 sessionId 的映射（需遍历，因反向索引不存在）
        userSessionMapping.entrySet().removeIf(entry -> sessionId.equals(entry.getValue()));
        if (removed != null) {
            log.info("[unregister][WebSocket 会话已注销] sessionId={}", sessionId);
        }
    }

    @Override
    public List<WebSocketSession> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    @Override
    public void registerUser(String sessionId, String username, String domain) {
        if (username == null || domain == null) {
            log.warn("[registerUser][username 或 domain 为空] sessionId={}", sessionId);
            return;
        }
        userSessionMapping.put(buildUserKey(username, domain), sessionId);
        log.info("[registerUser][已注册坐席身份映射] username={}, domain={}, sessionId={}",
                username, domain, sessionId);
    }

    @Override
    public boolean isSessionAlive(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        return session != null && session.isOpen();
    }

    /**
     * 构建 userSessionMapping 的 Key
     *
     * @param username 坐席分机号
     * @param domain   域名
     * @return 形如 "1001@ipcc.local" 的复合 Key
     */
    private String buildUserKey(String username, String domain) {
        return username + ":" + domain;
    }
}
