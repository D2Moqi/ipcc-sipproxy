package cn.ipcc.sipproxy.defaults.interceptor;

import cn.ipcc.sipproxy.api.interceptor.SipMessageInterceptor;
import lombok.extern.slf4j.Slf4j;

import javax.sip.message.Message;

/**
 * SIP 消息拦截器空实现（默认）
 * <p>
 * 设计意图：父程序未实现 {@link SipMessageInterceptor} 时的兜底实现，
 * 所有方法返回 false（不拦截），sipproxy 按默认逻辑转发。
 * <p>
 * 父程序实现 {@link SipMessageInterceptor} 接口并注册为 Bean 即可覆盖此默认实现，
 * 典型场景：REFER 转接的 ESL 编排（originate/bridge/hold/kill）。
 *
 * @author ipcc
 */
@Slf4j
public class NoopSipMessageInterceptor implements SipMessageInterceptor {

    @Override
    public boolean preWsToSip(Message message) {
        return false;
    }

    @Override
    public boolean preSipToWs(Message message) {
        return false;
    }
}
