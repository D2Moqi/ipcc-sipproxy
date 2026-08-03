package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.interceptor.SipMessageInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Message;

/**
 * SIP 消息拦截器扩展点自定义实现（替代 {@code NoopSipMessageInterceptor}）。
 * <p>
 * 用途：在 SIP 消息转发前后插入自定义逻辑。核心场景为 REFER 转接的 ESL 编排
 * （父程序在拦截器内调用 FsClient / FsCallCacheService 完成 originate/bridge/hold/kill 等话务操作），
 * sipproxy 仅负责 SIP 信令转发，不直接连接 FreeSWITCH。
 * <p>
 * 数据来源：无。
 * <p>
 * 与默认实现的差异：无行为差异，所有方法返回 false（不拦截，使用 sipproxy 默认转发逻辑）。
 * 本实现显式标注 {@code @Component} 注册为 Bean，覆盖默认的 {@code @ConditionalOnMissingBean} 实现，
 * 演示父程序接管拦截器扩展点的集成方式。父程序如需实现 REFER 转接 ESL 编排，
 * 可在 {@link #preWsToSip(Message)} 中检测 REFER 方法并返回 true 接管。
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomSipMessageInterceptor implements SipMessageInterceptor {

    /**
     * WS → SIP 转发前拦截（不拦截）。
     * <p>
     * 触发场景：坐席通过 WebSocket 发送 SIP 消息（如 REFER 转接请求），sipproxy 准备转发到 FS/第三方前。
     *
     * @param message SIP 消息（坐席发出的 Request）
     * @return 永远返回 false（继续走 sipproxy 默认转发逻辑）
     */
    @Override
    public boolean preWsToSip(Message message) {
        log.debug("[preWsToSip][不拦截，继续默认转发] message={}", message.getClass().getSimpleName());
        return false;
    }

    /**
     * SIP → WS 转发前拦截（不拦截）。
     * <p>
     * 触发场景：FS/第三方发送 SIP 消息到坐席，sipproxy 准备转发到 WebSocket 前。
     *
     * @param message SIP 消息（FS/第三方发来的 Request 或 Response）
     * @return 永远返回 false（继续走 sipproxy 默认转发逻辑）
     */
    @Override
    public boolean preSipToWs(Message message) {
        log.debug("[preSipToWs][不拦截，继续默认转发] message={}", message.getClass().getSimpleName());
        return false;
    }
}
