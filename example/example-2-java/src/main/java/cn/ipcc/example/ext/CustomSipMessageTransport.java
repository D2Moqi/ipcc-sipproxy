package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.transport.SipMessageTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Message;

/**
 * SIP 消息传输扩展点自定义实现（替代 {@code DefaultSipMessageTransport}）。
 * <p>
 * 用途：自定义 SIP 消息的传输方式（如自定义协议栈适配、走父程序的统一信令网关）。
 * 调用时机：sipproxy 在需要主动发送 SIP 消息（如构造响应、转发请求）时调用。
 * <p>
 * 数据来源：无。
 * <p>
 * 与默认实现的差异：无行为差异，均为空实现（不接管传输，由 JAIN-SIP 内置 UDP/TCP 传输处理）。
 * 本实现显式标注 {@code @Component} 注册为 Bean，覆盖默认的 {@code @ConditionalOnMissingBean} 实现，
 * 演示父程序接管传输扩展点的集成方式。父程序如需自定义传输（如走统一信令网关），
 * 可在此方法中实现自定义发送逻辑。
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomSipMessageTransport implements SipMessageTransport {

    /**
     * 发送 SIP 消息（空实现，不接管传输）。
     * <p>
     * 设计说明：空实现表示不接管消息传输，sipproxy 使用 JAIN-SIP 内置的 UDP/TCP 传输。
     *
     * @param message    SIP 消息（Request 或 Response）
     * @param targetHost 目标主机（IP 或域名）
     * @param targetPort 目标端口
     * @param transport  传输协议（udp / tcp / tls）
     */
    @Override
    public void send(Message message, String targetHost, int targetPort, String transport) {
        log.debug("[send][空实现，不接管传输，由 JAIN-SIP 内置传输处理] target={}:{}, transport={}",
                targetHost, targetPort, transport);
    }
}
