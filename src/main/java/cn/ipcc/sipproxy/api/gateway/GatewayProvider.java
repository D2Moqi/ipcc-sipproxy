package cn.ipcc.sipproxy.api.gateway;

import cn.ipcc.sipproxy.support.model.GatewayInfo;

import java.util.List;

/**
 * 网关查询扩展点
 * <p>
 * 父程序实现该接口，为 sipproxy 提供第三方 SIP 网关查询能力。
 * 用于出局 INVITE 路由选择、来源 IP 反查网关等场景，避免 sipproxy 直接依赖父程序的
 * FsSipGatewayService / FsSipGatewayMapper。
 */
public interface GatewayProvider {

    /**
     * 按网关 ID 查询
     *
     * @param gatewayId 网关 ID（字符串形式）
     * @return 网关信息（不存在返回 null）
     */
    GatewayInfo getGatewayById(String gatewayId);

    /**
     * 按网关名称查询
     *
     * @param gatewayName 网关名称（唯一标识）
     * @return 网关信息（不存在返回 null）
     */
    GatewayInfo getGatewayByName(String gatewayName);

    /**
     * 获取所有外部网关列表
     * <p>
     * 用于按来源 IP 反查网关（识别 THIRD_PARTY 来源），以及路由选择时遍历候选网关。
     *
     * @return 外部网关列表（空列表表示无配置）
     */
    List<GatewayInfo> listGateways();
}
