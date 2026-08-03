package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.media.SdpProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Message;

/**
 * SDP 处理扩展点自定义实现（替代 {@code DefaultSdpProcessor}）。
 * <p>
 * 用途：自定义 SDP 媒体协商逻辑（如 ICE 候选替换、编解码过滤、媒体流重定向到媒体服务器）。
 * 调用时机：sipproxy 在转发 INVITE/200 OK（含 SDP）前调用。
 * <p>
 * 数据来源：无（透传实现）。
 * <p>
 * 与默认实现的差异：无行为差异，均为直接返回传入消息不修改 SDP。
 * 本实现显式标注 {@code @Component} 注册为 Bean，覆盖默认的 {@code @ConditionalOnMissingBean} 实现，
 * 演示父程序接管 SDP 处理扩展点的集成方式。父程序如需修改 SDP（如替换 ICE 候选），
 * 可在此方法中构造新消息返回。
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomSdpProcessor implements SdpProcessor {

    /**
     * 处理消息中的 SDP（透传，不修改）。
     *
     * @param message SIP 消息（含 SDP body）
     * @return 原样返回传入的消息（不修改 SDP 内容）
     */
    @Override
    public Message process(Message message) {
        log.debug("[process][透传 SDP，不修改] message={}", message.getClass().getSimpleName());
        return message;
    }
}
