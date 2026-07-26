package cn.ipcc.sipproxy.cluster;

import cn.ipcc.sipproxy.websocket.WsSessionManager;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地 WebSocket 消息发送器（单实例部署）
 * <p>
 * 启用条件：{@code sipproxy.cluster.sender-type=local}（默认启用，matchIfMissing=true）。
 * <p>
 * 设计思路：单实例部署时无需 MQ 中间件，发送与接收均在同实例内完成。
 * {@link #send(SipWsBroadcastMessage)} 直接回调本实例注册的 {@link BroadcastReceiveCallback}，
 * 由 {@link ClusterBroadcastConsumer} 转发到本地 {@link WsSessionManager}。
 */
@Slf4j
public class LocalWsMessageSender implements WsMessageSender {

    /** 接收回调，由 ClusterBroadcastConsumer 通过 onReceive 注册 */
    private BroadcastReceiveCallback callback;

    @Override
    public void send(SipWsBroadcastMessage message) {
        // 本地模式：直接回调，无需 MQ 中间件
        if (callback != null) {
            callback.onReceive(message);
        } else {
            log.warn("[send][本地发送器未注册回调,消息被丢弃] target={}", message.getTarget());
        }
    }

    @Override
    public void onReceive(BroadcastReceiveCallback callback) {
        this.callback = callback;
    }
}
