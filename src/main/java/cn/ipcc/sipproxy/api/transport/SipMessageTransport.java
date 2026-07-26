package cn.ipcc.sipproxy.api.transport;

import javax.sip.message.Message;

/**
 * SIP 消息传输接入扩展点（可选实现）
 * <p>
 * 父程序可实现该接口，自定义 SIP 消息的传输方式（如自定义协议栈适配、
 * 走父程序的统一信令网关等）。默认实现使用 JAIN-SIP 内置的 UDP/TCP 传输。
 * <p>
 * 调用时机：sipproxy 在需要主动发送 SIP 消息（如构造响应、转发请求）时调用。
 */
public interface SipMessageTransport {

    /**
     * 发送 SIP 消息
     *
     * @param message    SIP 消息（Request 或 Response）
     * @param targetHost 目标主机（IP 或域名）
     * @param targetPort 目标端口
     * @param transport  传输协议（udp / tcp / tls）
     */
    void send(Message message, String targetHost, int targetPort, String transport);
}
