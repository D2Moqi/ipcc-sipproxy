package cn.ipcc.sipproxy.defaults.gateway;

import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * 网关查询默认实现
 * <p>
 * 设计意图：父程序未实现 {@link GatewayProvider} 时的兜底实现。
 * 当容器中存在 {@link JdbcTemplate}（即父程序配置了数据源）时，查询 H2 seed 数据表 sip_gateway，
 * 支持按 ID、按 address:port 查询网关及列出启用网关（status=0 表示启用），使 sipproxy 在无父程序扩展实现时
 * 仍可基于内置示例数据完成出局信令改写与来源识别；
 * 当 JdbcTemplate 为 null（父程序未配置数据源）时返回 null/空列表，保持向后兼容。
 * <p>
 * 注意：sip_gateway.id 为 BIGINT，而 {@link GatewayInfo#getId()} 为 String，映射时做 Long→String 转换；
 * 网关状态约定与坐席/FS 节点相反：status=0 表示启用，status=1 表示禁用。
 * <p>
 * 父程序实现 {@link GatewayProvider} 接口并注册为 Bean 即可覆盖此默认实现，
 * 为 sipproxy 提供第三方 SIP 网关查询能力。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultGatewayProvider implements GatewayProvider {

    /** 可选的 JDBC 查询模板，为 null 表示父程序未配置数据源，此时退化为返回 null/空列表 */
    private final JdbcTemplate jdbcTemplate;

    /** 网关结果集映射器：手动映射以处理 id 列 BIGINT→String 的类型转换及可空整数字段 */
    private static final RowMapper<GatewayInfo> GATEWAY_ROW_MAPPER = (rs, rowNum) -> mapGateway(rs);

    public DefaultGatewayProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public GatewayInfo getGatewayById(String gatewayId) {
        if (jdbcTemplate == null) {
            log.debug("[getGatewayById][默认实现返回null，父程序未提供网关查询且未配置数据源] gatewayId={}", gatewayId);
            return null;
        }
        // gatewayId 为字符串形式，sip_gateway.id 为 BIGINT，需转换为 Long 进行查询
        Long id;
        try {
            id = Long.parseLong(gatewayId);
        } catch (NumberFormatException e) {
            log.debug("[getGatewayById][gatewayId 非数字，无法查询] gatewayId={}", gatewayId);
            return null;
        }
        try {
            List<GatewayInfo> list = jdbcTemplate.query(
                    "SELECT * FROM sip_gateway WHERE id = ?",
                    GATEWAY_ROW_MAPPER, id);
            if (list.isEmpty()) {
                log.debug("[getGatewayById][H2 查询无记录] gatewayId={}", gatewayId);
                return null;
            }
            return list.get(0);
        } catch (Exception e) {
            // 表不存在或查询异常时降级为 null，避免阻断 sipproxy 出局信令改写链路
            log.warn("[getGatewayById][H2 查询异常，降级返回null] gatewayId={}, msg={}", gatewayId, e.getMessage());
            return null;
        }
    }

    @Override
    public GatewayInfo getGatewayByAddress(String address, Integer port) {
        if (jdbcTemplate == null) {
            log.debug("[getGatewayByAddress][默认实现返回null，父程序未提供网关查询且未配置数据源] address={}, port={}", address, port);
            return null;
        }
        try {
            List<GatewayInfo> list = jdbcTemplate.query(
                    "SELECT * FROM sip_gateway WHERE address = ? AND port = ?",
                    GATEWAY_ROW_MAPPER, address, port);
            if (list.isEmpty()) {
                log.debug("[getGatewayByAddress][H2 查询无记录] address={}, port={}", address, port);
                return null;
            }
            return list.get(0);
        } catch (Exception e) {
            log.warn("[getGatewayByAddress][H2 查询异常，降级返回null] address={}, port={}, msg={}", address, port, e.getMessage());
            return null;
        }
    }

    @Override
    public List<GatewayInfo> listEnabledGateways() {
        if (jdbcTemplate == null) {
            log.debug("[listEnabledGateways][默认实现返回空列表，父程序未提供网关列表且未配置数据源]");
            return Collections.emptyList();
        }
        try {
            // 网关状态约定：status=0 表示启用，status=1 表示禁用
            return jdbcTemplate.query(
                    "SELECT * FROM sip_gateway WHERE status = 0",
                    GATEWAY_ROW_MAPPER);
        } catch (Exception e) {
            // 表不存在或查询异常时降级为空列表，避免阻断 sipproxy 来源识别链路
            log.warn("[listEnabledGateways][H2 查询异常，降级返回空列表] msg={}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将 H2 结果集手动映射为 {@link GatewayInfo}
     * <p>
     * 设计要点：
     * <ul>
     *   <li>id 列为 BIGINT，模型字段为 String，通过 String.valueOf 转换</li>
     *   <li>可空整数字段（auth_port/retry_seconds/ping_seconds/expire_seconds）使用 getObject 保留 null，避免 getInt 返回 0 误判</li>
     * </ul>
     *
     * @param rs ResultSet
     * @return GatewayInfo 实例
     * @throws SQLException 列读取异常
     */
    private static GatewayInfo mapGateway(ResultSet rs) throws SQLException {
        GatewayInfo g = new GatewayInfo();
        g.setId(String.valueOf(rs.getLong("id")));
        g.setName(rs.getString("name"));
        g.setAddress(rs.getString("address"));
        g.setPort(rs.getObject("port", Integer.class));
        g.setExternalLineNumber(rs.getString("external_line_number"));
        g.setFromDomain(rs.getString("from_domain"));
        g.setCallerIdInFrom(rs.getObject("caller_id_in_from", Integer.class));
        g.setAuthType(rs.getObject("auth_type", Integer.class));
        g.setTransportProtocol(rs.getObject("transport_protocol", Integer.class));
        g.setAuthAddress(rs.getString("auth_address"));
        g.setAuthPort(rs.getObject("auth_port", Integer.class));
        g.setUsername(rs.getString("username"));
        g.setPassword(rs.getString("password"));
        g.setRetrySeconds(rs.getObject("retry_seconds", Integer.class));
        g.setPingSeconds(rs.getObject("ping_seconds", Integer.class));
        g.setExpireSeconds(rs.getObject("expire_seconds", Integer.class));
        g.setStatus(rs.getObject("status", Integer.class));
        return g;
    }
}
