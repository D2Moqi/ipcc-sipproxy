package cn.ipcc.sipproxy.defaults.media;

import cn.ipcc.sipproxy.api.media.SdpProcessor;
import lombok.extern.slf4j.Slf4j;

import javax.sip.message.Message;

/**
 * SDP 处理默认实现（透传）
 * <p>
 * 设计意图：默认不对 SDP 做任何处理，直接返回原消息。
 * 父程序若需自定义 SDP 媒体协商逻辑（如 ICE 候选替换、编解码过滤、
 * 媒体流重定向到媒体服务器等），实现 {@link SdpProcessor} 接口注册为 Bean 即可覆盖。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultSdpProcessor implements SdpProcessor {

    /**
     * 默认透传 SDP
     *
     * @param message SIP 消息（含 SDP body）
     * @return 原消息（不做修改）
     */
    @Override
    public Message process(Message message) {
        return message;
    }
}
