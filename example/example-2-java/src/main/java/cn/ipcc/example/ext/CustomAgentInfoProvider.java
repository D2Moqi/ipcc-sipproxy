package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.agent.AgentInfoProvider;
import cn.ipcc.sipproxy.support.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 坐席信息查询扩展点自定义实现（替代 {@code DefaultAgentInfoProvider}）。
 * <p>
 * 用途：为 sipproxy 提供坐席信息查询能力，用于 INVITE/REGISTER 请求处理时获取坐席元数据。
 * <p>
 * 数据来源：硬编码单条坐席记录（不依赖数据库），用于演示集成与本地联调。
 * <ul>
 *   <li>extension=1001, domain=sipproxy.example</li>
 *   <li>password=123456（明文，供 Digest 认证计算 HA1）</li>
 *   <li>agentId=1, displayName=测试坐席</li>
 * </ul>
 * <p>
 * 与默认实现的差异：
 * <ul>
 *   <li>默认实现 {@code DefaultAgentInfoProvider} 通过 JdbcTemplate 查询 H2 seed 数据；</li>
 *   <li>本实现直接硬编码返回，去除数据库依赖，简化部署。</li>
 * </ul>
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomAgentInfoProvider implements AgentInfoProvider {

    /** 硬编码坐席分机号 */
    private static final String AGENT_EXTENSION = "1001";
    /** 硬编码坐席域名 */
    private static final String AGENT_DOMAIN = "sipproxy.example";
    /** 硬编码坐席密码（明文，供 {@code CustomSipAuthenticationProvider} 计算 Digest HA1） */
    private static final String AGENT_PASSWORD = "123456";
    /** 硬编码坐席 ID */
    private static final Long AGENT_ID = 1L;
    /** 硬编码坐席显示名称 */
    private static final String AGENT_DISPLAY_NAME = "测试坐席";

    /**
     * 查询坐席信息（硬编码匹配）。
     * <p>
     * 匹配规则：extension=1001 且 domain=sipproxy.example 时返回硬编码 AgentInfo，否则返回 null。
     *
     * @param extension 分机号
     * @param domain    域名（可空，空表示不区分域名；本实现要求严格匹配 sipproxy.example）
     * @return 命中硬编码坐席返回 AgentInfo，否则 null
     */
    @Override
    public AgentInfo getAgent(String extension, String domain) {
        if (!AGENT_EXTENSION.equals(extension) || !AGENT_DOMAIN.equals(domain)) {
            log.debug("[getAgent][未命中硬编码坐席] extension={}, domain={}", extension, domain);
            return null;
        }
        AgentInfo agent = new AgentInfo();
        agent.setExtension(AGENT_EXTENSION);
        agent.setDomain(AGENT_DOMAIN);
        agent.setPassword(AGENT_PASSWORD);
        agent.setAgentId(AGENT_ID);
        agent.setDisplayName(AGENT_DISPLAY_NAME);
        log.debug("[getAgent][命中硬编码坐席] extension={}, domain={}, agentId={}", extension, domain, AGENT_ID);
        return agent;
    }
}
