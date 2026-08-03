package cn.ipcc.sipproxy.defaults.security;

import cn.ipcc.sipproxy.api.security.IpWhitelist;
import lombok.extern.slf4j.Slf4j;

/**
 * IP 白名单默认实现（全部放行）
 * <p>
 * 设计意图：适用于内网部署无第三方网关接入的场景，或父程序依赖外部防火墙/安全组做 IP 过滤。
 * 父程序若需自定义白名单逻辑（如从数据库加载 IP 列表），实现 {@link IpWhitelist} 接口注册为 Bean 即可覆盖。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultIpWhitelist implements IpWhitelist {

    /**
     * 默认全部放行
     *
     * @param ip 客户端 IP（IPv4 或 IPv6）
     * @return 始终返回 true（由父程序覆盖时实现真实校验）
     */
    @Override
    public boolean isAllowed(String ip) {
        return true;
    }
}
