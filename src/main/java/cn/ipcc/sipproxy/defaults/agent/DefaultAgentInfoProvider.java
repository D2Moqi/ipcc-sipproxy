package cn.ipcc.sipproxy.defaults.agent;

import cn.ipcc.sipproxy.api.agent.AgentInfoProvider;
import cn.ipcc.sipproxy.support.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 坐席信息查询默认实现
 * <p>
 * 设计意图：父程序未实现 {@link AgentInfoProvider} 时的兜底实现。
 * 当容器中存在 {@link JdbcTemplate}（即父程序配置了数据源）时，查询 H2 seed 数据表 sip_agent，
 * 使 sipproxy 在无父程序扩展实现时仍可基于内置示例数据完成注册认证与呼叫路由；
 * 当 JdbcTemplate 为 null（父程序未配置数据源）时返回 null，保持向后兼容，服务可启动但功能受限。
 * <p>
 * 父程序实现 {@link AgentInfoProvider} 接口并注册为 Bean 即可覆盖此默认实现，
 * 为 sipproxy 提供坐席信息查询能力（分机号、域名、密码等）。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultAgentInfoProvider implements AgentInfoProvider {

    /** 可选的 JDBC 查询模板，为 null 表示父程序未配置数据源，此时退化为返回 null */
    private final JdbcTemplate jdbcTemplate;

    public DefaultAgentInfoProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AgentInfo getAgent(String extension, String domain) {
        if (jdbcTemplate == null) {
            log.debug("[getAgent][默认实现返回null，父程序未提供坐席查询且未配置数据源] extension={}, domain={}", extension, domain);
            return null;
        }
        try {
            // 查询 H2 seed 数据表 sip_agent，按分机号 + 域名定位坐席记录
            // BeanPropertyRowMapper 自动完成下划线列名到驼峰字段的映射，id 列无对应字段会被忽略
            List<AgentInfo> list = jdbcTemplate.query(
                    "SELECT * FROM sip_agent WHERE extension = ? AND domain = ?",
                    new BeanPropertyRowMapper<>(AgentInfo.class),
                    extension, domain);
            if (list.isEmpty()) {
                log.debug("[getAgent][H2 查询无记录] extension={}, domain={}", extension, domain);
                return null;
            }
            return list.get(0);
        } catch (Exception e) {
            // 表不存在或查询异常时降级为 null，避免阻断 sipproxy 注册认证链路
            log.warn("[getAgent][H2 查询异常，降级返回null] extension={}, domain={}, msg={}", extension, domain, e.getMessage());
            return null;
        }
    }
}
