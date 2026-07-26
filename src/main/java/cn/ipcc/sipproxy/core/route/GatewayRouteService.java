package cn.ipcc.sipproxy.core.route;

import cn.ipcc.sipproxy.support.model.GatewayInfo;

import java.util.List;

/**
 * 网关路由服务
 * <p>
 * 需求: 根据网关ID查询网关配置,支持备选网关Failover
 * <p>
 * 设计意图：迁移自原 cc-server 的 GatewayRouteService / GatewayRouteServiceImpl，
 * 移除对 FsSipGatewayMapper（MyBatis-Plus）的直接依赖，改为通过 {@code GatewayProvider}
 * 扩展点委托父程序查询网关数据，sipproxy 不直接访问数据库。
 * 返回类型由 FsSipGatewayDO 改为 {@link GatewayInfo}（sipproxy 自有数据模型）。
 */
public interface GatewayRouteService {

    /**
     * 根据网关ID查询网关配置
     * <p>
     * 需求: 通过网关标识查询外部网关配置信息
     * 预期结果: 返回匹配的外部网关配置,不存在返回null
     * 处理逻辑: 优先按网关名称查询(SIP上下文中gatewayId通常为网关名称),
     *           若未找到且gatewayId为数字则按主键ID查询,同时限定网关类型为EXTERNAL
     *
     * @param gatewayId 网关ID(网关名称或数据库主键)
     * @return 网关配置,不存在返回null
     */
    GatewayInfo getGatewayById(String gatewayId);

    /**
     * 查询备选网关列表(排除指定网关)
     * <p>
     * 需求: 主网关不可用时,获取可用的备选外部网关列表
     * 预期结果: 返回排除主网关后的外部网关列表,按ID升序排序(作为优先级依据)
     * 处理逻辑: 查询所有EXTERNAL类型网关,排除指定网关,按ID升序返回
     *
     * @param excludeGatewayId 需要排除的网关ID(主网关)
     * @return 备选网关列表,按优先级排序
     */
    List<GatewayInfo> getAlternativeGateways(String excludeGatewayId);

    /**
     * 获取下一个可用网关(主网关失败时使用)
     * <p>
     * 需求: 主网关不可用(503/超时)时,按优先级尝试备选网关
     * 预期结果: 返回下一个可用的备选网关,无可用网关返回null
     * 处理逻辑: 查询备选网关列表,按优先级返回第一个
     *
     * @param failedGatewayId 失败的网关ID(主网关)
     * @return 下一个可用的备选网关,无可用网关返回null
     */
    GatewayInfo getNextAvailableGateway(String failedGatewayId);

}
