package cn.ipcc.sipproxy.api.security;

/**
 * IP 白名单扩展点（可选实现）
 * <p>
 * 父程序可实现该接口，提供第三方网关 IP 白名单校验。
 * 默认实现全部放行（适用于内网部署无第三方网关接入的场景）。
 * <p>
 * 调用时机：sipproxy 在收到第三方 SIP 请求时调用，校验来源 IP 是否在白名单内，
 * 不在白名单的请求直接返回 403 Forbidden。
 */
public interface IpWhitelist {

    /**
     * 检查 IP 是否在白名单内
     *
     * @param ip 客户端 IP（IPv4 或 IPv6）
     * @return 在白名单内返回 true，否则返回 false
     */
    boolean isAllowed(String ip);
}
