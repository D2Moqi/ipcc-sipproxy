package cn.ipcc.sipproxy.autoconfigure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * sipproxy 会话存储自动配置
 * <p>
 * 设计意图：声明 sipproxy 模块对会话存储（Redis）的依赖关系，确保容器启动时 Redis 可用。
 * <p>
 * 当前实现：会话存储职责由 {@code SipSessionManager}（{@code @Component}）承担，
 * 内部直接使用 {@link StringRedisTemplate} 操作 Redis，Key 前缀统一为 {@code ipcc:sipproxy:}。
 * 会话相关 Key 包括：
 * <ul>
 *   <li>{@code ipcc:sipproxy:session:info:{callId}} — 会话信息</li>
 *   <li>{@code ipcc:sipproxy:session:node:{callId}} — 会话-节点映射</li>
 *   <li>{@code ipcc:sipproxy:session:register:{extension}} — 注册信息</li>
 *   <li>{@code ipcc:sipproxy:session:user:{username@domain}} — 用户-会话映射</li>
 * </ul>
 * <p>
 * 启用条件：类路径存在 {@link StringRedisTemplate}（父程序引入 spring-boot-starter-data-redis）。
 * 若父程序未配置 Redis，{@code SipSessionManager} 注入 {@link StringRedisTemplate} 时将失败，
 * 容器启动报错（fail-fast），避免运行期才发现会话无法持久化。
 * <p>
 * 集群部署说明：所有会话状态存储在 Redis 集群中，多实例 sipproxy 节点共享会话数据，
 * 支持负载均衡与故障转移。WS 集群广播通过 {@code SipProxyClusterAutoConfiguration} 配置。
 *
 * @author ipcc
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "sipproxy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SipProxySessionAutoConfiguration {

    /**
     * 构造时输出会话存储初始化日志，便于排查 Redis 配置缺失问题
     */
    public SipProxySessionAutoConfiguration() {
        log.info("[SipProxySessionAutoConfiguration][会话存储初始化] 使用 Redis 作为会话存储后端");
    }
}
