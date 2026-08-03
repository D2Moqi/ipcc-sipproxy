package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.security.IpWhitelist;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * IP 白名单扩展点自定义实现（替代 {@code DefaultIpWhitelist}）。
 * <p>
 * 用途：为 sipproxy 提供第三方网关 IP 白名单校验能力。
 * <p>
 * 数据来源：无（全部放行策略）。
 * <p>
 * 与默认实现的差异：无行为差异，均为全部放行。默认实现适用于内网部署无第三方网关接入的场景；
 * 本实现显式标注 {@code @Component} 注册为 Bean，覆盖默认的 {@code @ConditionalOnMissingBean} 实现，
 * 演示父程序接管 IP 白名单扩展点的集成方式。
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomIpWhitelist implements IpWhitelist {

    /**
     * 检查 IP 是否在白名单内（全部放行）。
     *
     * @param ip 客户端 IP（IPv4 或 IPv6）
     * @return 永远返回 true（演示环境全部放行，不限制任何来源 IP）
     */
    @Override
    public boolean isAllowed(String ip) {
        log.debug("[isAllowed][全部放行] ip={}", ip);
        return true;
    }
}
