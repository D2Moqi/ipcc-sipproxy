package cn.ipcc.sipproxy.api.security;

/**
 * SIP 速率限制扩展点（可选实现）
 * <p>
 * 父程序可实现该接口，自定义 SIP 请求速率限制逻辑（如令牌桶、滑动窗口）。
 * 默认实现不做限制（全部放行）。
 * <p>
 * 调用时机：sipproxy 在收到 SIP 请求后、路由分发前调用，用于防止单 IP 恶意注册/呼叫刷量。
 */
public interface SipRateLimiter {

    /**
     * 检查是否允许请求通过
     *
     * @param sourceIp 来源 IP（客户端真实 IP，非 Via 头）
     * @param method   SIP 方法（REGISTER / INVITE / OPTIONS 等）
     * @return 允许返回 true，拒绝返回 false（sipproxy 将返回 429 Too Many Requests）
     */
    boolean tryAcquire(String sourceIp, String method);
}
