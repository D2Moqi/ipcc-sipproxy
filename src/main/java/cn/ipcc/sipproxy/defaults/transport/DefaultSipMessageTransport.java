package cn.ipcc.sipproxy.defaults.transport;

import cn.ipcc.sipproxy.api.transport.SipMessageTransport;
import lombok.extern.slf4j.Slf4j;

import javax.sip.message.Message;

/**
 * SIP 消息传输默认实现（空实现）
 * <p>
 * 设计意图：父程序未实现 {@link SipMessageTransport} 时的兜底实现，
 * 空实现表示不接管消息传输，sipproxy 使用 JAIN-SIP 内置的 UDP/TCP 传输。
 * <p>
 * 父程序实现 {@link SipMessageTransport} 接口并注册为 Bean 即可覆盖此默认实现，
 * 自定义 SIP 消息的传输方式（如自定义协议栈适配、走父程序的统一信令网关等）。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultSipMessageTransport implements SipMessageTransport {

    @Override
    public void send(Message message, String targetHost, int targetPort, String transport) {
        log.debug("[send][默认实现不接管传输，由 JAIN-SIP 内置传输处理] target={}:{}, transport={}",
                targetHost, targetPort, transport);
    }
}
