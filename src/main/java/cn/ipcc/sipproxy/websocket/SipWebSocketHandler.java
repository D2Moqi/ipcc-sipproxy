package cn.ipcc.sipproxy.websocket;

import cn.ipcc.sipproxy.core.SipProxyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * SIP WebSocket 消息处理器
 * <p>
 * 从原 {@code cc.websocket.core.handler.SipWebSocketMessageHandler} 迁移核心逻辑。
 * <p>
 * 职责：
 * <ol>
 *   <li>连接建立：注册到 {@link WsSessionManager}</li>
 *   <li>文本消息：通过 {@link SipFrameReassembler} 重组 SIP 分片，
 *       调用 {@link SipProxyService#handleWebSocketSipMessage} 处理完整 SIP 消息</li>
 *   <li>连接关闭：清理 WsSessionManager + SipFrameReassembler 缓冲区 +
 *       调用 {@link SipProxyService#cleanupRegisterInfo} 清理注册信息</li>
 *   <li>传输异常：记录日志，框架会触发 afterConnectionClosed</li>
 * </ol>
 */
@Slf4j
public class SipWebSocketHandler extends TextWebSocketHandler {

    /** sipproxy 核心服务，处理完整 SIP 消息 */
    private final SipProxyService sipProxyService;

    /** WebSocket 会话管理器 */
    private final WsSessionManager wsSessionManager;

    /** SIP 消息分片重组器 */
    private final SipFrameReassembler sipFrameReassembler;

    /**
     * 构造 SIP WebSocket 消息处理器
     *
     * @param sipProxyService     sipproxy 核心服务
     * @param wsSessionManager    WebSocket 会话管理器
     * @param sipFrameReassembler SIP 消息分片重组器
     */
    public SipWebSocketHandler(SipProxyService sipProxyService,
                               WsSessionManager wsSessionManager,
                               SipFrameReassembler sipFrameReassembler) {
        this.sipProxyService = sipProxyService;
        this.wsSessionManager = wsSessionManager;
        this.sipFrameReassembler = sipFrameReassembler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        wsSessionManager.register(session);
        log.info("[afterConnectionEstablished][WebSocket 连接建立] sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        String payload = message.getPayload();
        // 更新最后活跃时间，供 ZombieSessionCleaner 判定
        session.getAttributes().put("lastActiveAt", System.currentTimeMillis());
        // 重组分片并逐条处理（单帧多消息场景会返回多条完整 SIP 消息）
        for (String sipMessage : sipFrameReassembler.reassemble(sessionId, payload)) {
            try {
                sipProxyService.handleWebSocketSipMessage(sessionId, sipMessage);
            } catch (Exception e) {
                // 单条消息处理失败不影响后续消息，避免连接被异常打断
                log.error("[handleTextMessage][处理 SIP 消息失败] sessionId={}", sessionId, e);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        wsSessionManager.unregister(sessionId);
        sipFrameReassembler.cleanup(sessionId);
        sipProxyService.cleanupRegisterInfo(sessionId);
        log.info("[afterConnectionClosed][WebSocket 连接关闭] sessionId={}, status={}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("[handleTransportError][WebSocket 传输错误] sessionId={}", session.getId(), exception);
    }
}
