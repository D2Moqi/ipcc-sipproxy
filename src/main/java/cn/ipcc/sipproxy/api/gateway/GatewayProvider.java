package cn.ipcc.sipproxy.api.gateway;

import cn.ipcc.sipproxy.support.model.GatewayInfo;

import java.util.List;

/**
 * 网关查询扩展点
 * <p>
 * 父程序实现该接口，为 sipproxy 提供第三方 SIP 网关查询能力。
 * 用于出局 INVITE 路由选择、来源 IP 反查网关等场景，避免 sipproxy 直接依赖父程序的
 * 数据访问层。
 */
public interface GatewayProvider {

    /**
     * 按网关 ID 查询
     *
     * @param gatewayId 网关 ID（字符串形式）
     * @return 网关信息（不存在或已禁用返回 null）
     */
    GatewayInfo getGatewayById(String gatewayId);

    /**
     * 按地址和端口查询网关（用于入呼来源识别）
     *
     * @param address 网关地址（IP或域名）
     * @param port 网关端口
     * @return 网关信息（不存在或已禁用返回 null）
     */
    GatewayInfo getGatewayByAddress(String address, Integer port);

    /**
     * 获取所有启用的网关列表
     * <p>
     * 用于按来源 IP 反查网关（识别 THIRD_PARTY 来源），以及路由选择时遍历候选网关。
     *
     * @return 启用的网关列表（空列表表示无配置）
     */
    List<GatewayInfo> listEnabledGateways();

    /**
     * 按注册账号查询网关（REGISTER 认证与来源识别用）
     * <p>
     * 设计要求：定义为 default 方法（缺省返回 null），避免破坏既有 GatewayProvider
     * 实现类编译（DefaultGatewayProvider、example 模块 CustomGatewayProvider 等），
     * 父程序实现覆盖即可支持注册型网关。
     *
     * @param username 注册账号（SIP REGISTER From/Authorization username）
     * @return 网关信息（不存在/禁用/未实现时返回 null）
     */
    default GatewayInfo getGatewayByUsername(String username) {
        return null;
    }
}
