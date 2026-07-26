package cn.ipcc.sipproxy.api.media;

import javax.sip.message.Message;

/**
 * SDP 处理扩展点（可选实现）
 * <p>
 * 父程序可实现该接口，自定义 SDP 媒体协商逻辑（如 ICE 候选替换、编解码过滤、
 * 媒体流重定向到媒体服务器等）。默认实现直接透传 SDP，不做修改。
 * <p>
 * 调用时机：sipproxy 在转发 INVITE/200 OK（含 SDP）前调用，父程序可修改 SDP 内容后返回新消息。
 */
public interface SdpProcessor {

    /**
     * 处理消息中的 SDP
     *
     * @param message SIP 消息（含 SDP body）
     * @return 处理后的消息（可为同一对象或新构造的消息）
     */
    Message process(Message message);
}
