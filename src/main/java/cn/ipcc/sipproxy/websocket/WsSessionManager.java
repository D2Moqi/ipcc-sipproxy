package cn.ipcc.sipproxy.websocket;

import org.springframework.web.socket.WebSocketSession;

import java.util.List;

/**
 * WebSocket 会话管理器接口
 * <p>
 * 负责管理 WebSocket 会话生命周期，并按 sessionId 发送 SIP 消息到对应坐席客户端。
 * 单实例部署时直接通过本地 {@link WebSocketSession} 发送；
 * 多实例部署时通过 {@code WsMessageSender} 抽象委托集群广播。
 * <p>
 * 设计意图：解耦 WebSocket 会话存储与消息发送逻辑，便于在 local / redis / MQ
 * 等不同集群拓扑下复用同一套上层接口。
 */
public interface WsSessionManager {

    /**
     * 发送 SIP 消息文本到指定 WebSocket 会话
     *
     * @param sessionId      WebSocket 会话 ID
     * @param messageContent SIP 消息文本（或任意文本）
     */
    void send(String sessionId, String messageContent);

    /**
     * 按 username + domain 查询 WebSocket 会话 ID
     *
     * @param username 坐席分机号
     * @param domain   域名
     * @return sessionId（不存在返回 null）
     */
    String getSessionIdByUser(String username, String domain);

    /**
     * 注册 WebSocket 会话（连接建立时调用）
     *
     * @param session WebSocket 会话
     */
    void register(WebSocketSession session);

    /**
     * 注销 WebSocket 会话（连接关闭时调用）
     *
     * @param sessionId 会话 ID
     */
    void unregister(String sessionId);

    /**
     * 获取所有 WebSocket 会话（用于僵尸清理）
     *
     * @return 所有会话列表
     */
    List<WebSocketSession> getAllSessions();

    /**
     * 注册 username + domain 与 sessionId 的映射
     * <p>
     * 触发场景：REGISTER 请求处理成功后由 {@code SipProxyService} 调用，
     * 用于后续按坐席身份路由 SIP 消息。
     *
     * @param sessionId WebSocket 会话 ID
     * @param username  坐席分机号
     * @param domain    域名
     */
    void registerUser(String sessionId, String username, String domain);
}
