package cn.ipcc.sipproxy.core.route;

import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.support.GatewayTypeEnum;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 网关路由服务实现
 * <p>
 * 需求: 根据网关ID查询网关配置,支持备选网关Failover
 * <p>
 * 处理逻辑: 通过 {@link GatewayProvider} 扩展点查询网关数据,限定 EXTERNAL 类型,
 * 支持按名称/ID查询及备选网关优先级排序。原 cc-server 实现直接使用 FsSipGatewayMapper
 * + MyBatis-Plus LambdaQueryWrapper,迁移后改为通过扩展点委托父程序查询,
 * sipproxy 不直接依赖父程序 ORM 层。
 */
@Slf4j
@Service
public class GatewayRouteServiceImpl implements GatewayRouteService {

    @Resource
    private GatewayProvider gatewayProvider;

    /**
     * 根据网关ID查询网关配置
     * <p>
     * 需求: 通过网关标识查询外部网关配置信息
     * 预期结果: 返回匹配的外部网关配置,不存在返回null
     * 处理逻辑:
     *   1. 优先按网关名称查询(SIP上下文中gatewayId通常为网关名称),校验类型为EXTERNAL
     *   2. 若名称查询无结果且gatewayId为数字,则按主键ID查询并校验类型为EXTERNAL
     *
     * @param gatewayId 网关ID(网关名称或数据库主键)
     * @return 网关配置,不存在返回null
     */
    @Override
    public GatewayInfo getGatewayById(String gatewayId) {
        // 优先按网关名称查询(SIP上下文中gatewayId通常为网关名称)
        GatewayInfo gateway = gatewayProvider.getGatewayByName(gatewayId);
        if (gateway != null && GatewayTypeEnum.EXTERNAL.getType().equals(gateway.getType())) {
            return gateway;
        }

        // 若名称查询无结果,尝试按主键ID查询
        try {
            Long id = Long.parseLong(gatewayId);
            GatewayInfo gatewayById = gatewayProvider.getGatewayById(String.valueOf(id));
            // 校验网关类型为EXTERNAL
            if (gatewayById != null && GatewayTypeEnum.EXTERNAL.getType().equals(gatewayById.getType())) {
                return gatewayById;
            }
        } catch (NumberFormatException ignored) {
            // gatewayId非数字,无需按ID查询
            log.debug("[getGatewayById][gatewayId={}非数字格式,跳过按主键ID查询]", gatewayId);
        }

        return null;
    }

    /**
     * 查询备选网关列表(排除指定网关)
     * <p>
     * 需求: 主网关不可用时,获取可用的备选外部网关列表
     * 预期结果: 返回排除主网关后的外部网关列表,按ID升序排序(作为优先级依据)
     * 处理逻辑:
     *   1. 查询所有EXTERNAL类型网关
     *   2. 排除主网关(按名称和ID双重排除,确保不遗漏)
     *   3. 按ID升序排序,作为优先级依据
     *
     * @param excludeGatewayId 需要排除的网关ID(主网关)
     * @return 备选网关列表,按优先级排序
     */
    @Override
    public List<GatewayInfo> getAlternativeGateways(String excludeGatewayId) {
        // 通过扩展点查询所有网关,过滤EXTERNAL类型,排除主网关,按ID升序排序
        List<GatewayInfo> allGateways = gatewayProvider.listGateways();
        if (allGateways == null || allGateways.isEmpty()) {
            return List.of();
        }
        return allGateways.stream()
                .filter(g -> GatewayTypeEnum.EXTERNAL.getType().equals(g.getType()))
                .filter(g -> !Objects.equals(g.getName(), excludeGatewayId)
                        && !Objects.equals(g.getId(), excludeGatewayId))
                .sorted(Comparator.comparing(GatewayInfo::getId, Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
    }

    /**
     * 获取下一个可用网关(主网关失败时使用)
     * <p>
     * 需求: 主网关不可用(503/超时)时,按优先级尝试备选网关
     * 预期结果: 返回下一个可用的备选网关,无可用网关返回null
     * 处理逻辑:
     *   1. 查询备选网关列表(已排除失败网关,按优先级排序)
     *   2. 返回列表中第一个网关(优先级最高的备选网关)
     *
     * @param failedGatewayId 失败的网关ID(主网关)
     * @return 下一个可用的备选网关,无可用网关返回null
     */
    @Override
    public GatewayInfo getNextAvailableGateway(String failedGatewayId) {
        List<GatewayInfo> alternatives = getAlternativeGateways(failedGatewayId);
        if (alternatives != null && !alternatives.isEmpty()) {
            // 返回优先级最高的备选网关(列表第一个元素)
            GatewayInfo nextGateway = alternatives.get(0);
            log.info("[getNextAvailableGateway][主网关{}失败,切换到备选网关:{}]",
                    failedGatewayId, nextGateway.getName());
            return nextGateway;
        }
        log.warn("[getNextAvailableGateway][主网关{}失败,无可用备选网关]", failedGatewayId);
        return null;
    }

}
