package cn.ipcc.sipproxy.api.gateway;

import javax.sip.message.Message;

/**
 * 消息来源识别扩展点（可选实现）
 * <p>
 * 父程序可实现该接口，自定义 SIP 消息来源识别逻辑。
 * 默认实现基于 User-Agent / Via IP 识别，区分 WEBSOCKET / FREESWITCH / THIRD_PARTY 三类来源。
 * <p>
 * 来源识别结果用于路由分发：WEBSOCKET 来源走 WS→SIP 转发，FREESWITCH/THIRD_PARTY 来源走 SIP→WS 转发。
 */
public interface MessageSourceIdentifier {

    /**
     * 识别消息来源
     *
     * @param message SIP 消息（Request 或 Response）
     * @return 来源标识（取值见 {@code cn.ipcc.sipproxy.support.SipProxyConstants}：WEBSOCKET / FREESWITCH / THIRD_PARTY）
     */
    String identifySource(Message message);
}
