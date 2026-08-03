package cn.ipcc.sipproxy.defaults.security;

import cn.ipcc.sipproxy.api.security.SipRateLimiter;
import lombok.extern.slf4j.Slf4j;

/**
 * SIP 速率限制默认实现（全部放行）
 * <p>
 * 设计意图：默认不限制请求速率，适用于功能验证与单实例低流量场景。
 * 父程序若需启用限流（如令牌桶/滑动窗口），实现 {@link SipRateLimiter} 接口注册为 Bean 即可覆盖。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultSipRateLimiter implements SipRateLimiter {

    /**
     * 默认全部放行
     *
     * @param sourceIp 来源 IP（客户端真实 IP，非 Via 头）
     * @param method   SIP 方法（REGISTER / INVITE / OPTIONS 等）
     * @return 始终返回 true（由父程序覆盖时实现真实限流）
     */
    @Override
    public boolean tryAcquire(String sourceIp, String method) {
        return true;
    }
}
