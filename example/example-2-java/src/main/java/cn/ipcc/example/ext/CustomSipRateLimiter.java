package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.security.SipRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SIP 速率限制扩展点自定义实现（替代 {@code DefaultSipRateLimiter}）。
 * <p>
 * 用途：为 sipproxy 提供 SIP 请求速率限制能力（如令牌桶、滑动窗口），防止单 IP 恶意注册/呼叫刷量。
 * <p>
 * 数据来源：无（全部放行策略）。
 * <p>
 * 与默认实现的差异：无行为差异，均为不限制。默认实现适用于本地联调与无刷量防护需求的场景；
 * 本实现显式标注 {@code @Component} 注册为 Bean，覆盖默认的 {@code @ConditionalOnMissingBean} 实现，
 * 演示父程序接管 SIP 限流扩展点的集成方式。
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomSipRateLimiter implements SipRateLimiter {

    /**
     * 检查是否允许请求通过（全部放行）。
     *
     * @param sourceIp 来源 IP（客户端真实 IP，非 Via 头）
     * @param method   SIP 方法（REGISTER / INVITE / OPTIONS 等）
     * @return 永远返回 true（演示环境不限流，所有请求均放行）
     */
    @Override
    public boolean tryAcquire(String sourceIp, String method) {
        log.debug("[tryAcquire][全部放行] sourceIp={}, method={}", sourceIp, method);
        return true;
    }
}
