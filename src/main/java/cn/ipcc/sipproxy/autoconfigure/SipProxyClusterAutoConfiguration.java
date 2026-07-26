package cn.ipcc.sipproxy.autoconfigure;

import cn.ipcc.sipproxy.cluster.ClusterBroadcastConsumer;
import cn.ipcc.sipproxy.cluster.LocalWsMessageSender;
import cn.ipcc.sipproxy.cluster.RedisWsMessageSender;
import cn.ipcc.sipproxy.cluster.WsMessageSender;
import cn.ipcc.sipproxy.websocket.WsSessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * sipproxy 集群广播自动配置
 * <p>
 * 设计意图：根据 {@code sipproxy.cluster.sender-type} 选择不同的 {@link WsMessageSender} 实现，
 * 支持 local / redis / rocketmq / rabbitmq / kafka 五种广播方式。
 * <p>
 * 同时通过 {@link EnableScheduling} 启用 {@code @Scheduled} 注解，使
 * {@code ZombieSessionCleaner} 的定时清理任务生效。
 * <p>
 * Bean 注册策略：
 * <ul>
 *   <li>{@code sender-type=local}（默认）：注册 {@link LocalWsMessageSender}</li>
 *   <li>{@code sender-type=redis}：注册 {@link RedisWsMessageSender} +
 *       {@link RedisMessageListenerContainer}（订阅广播 channel）</li>
 *   <li>{@code sender-type=kafka/rabbitmq/rocketmq}：由对应 {@code @ConditionalOnClass}
 *       保护的条件 Bean 注册（参见 {@code KafkaWsMessageSender} 等占位实现）</li>
 * </ul>
 * <p>
 * 默认启用：移除原 {@code @ConditionalOnProperty} 限制，确保 local 默认实现可用。
 */
@AutoConfiguration
@EnableScheduling
public class SipProxyClusterAutoConfiguration {

    // ==================== Local 实现（默认） ====================

    /**
     * 本地 WebSocket 消息发送器
     * <p>
     * 启用条件：{@code sipproxy.cluster.sender-type=local}（默认启用，matchIfMissing=true）。
     * 通过 {@code @ConditionalOnMissingBean} 允许父程序覆盖。
     *
     * @return LocalWsMessageSender 实例
     */
    @Bean
    @ConditionalOnMissingBean(WsMessageSender.class)
    @ConditionalOnProperty(prefix = "sipproxy.cluster", name = "sender-type",
            havingValue = "local", matchIfMissing = true)
    public LocalWsMessageSender localWsMessageSender() {
        return new LocalWsMessageSender();
    }

    // ==================== Redis 实现 ====================

    /**
     * 注册 Redis pub/sub 广播监听容器
     * <p>
     * 启用条件：
     * <ul>
     *   <li>类路径存在 {@link StringRedisTemplate}（父程序引入 spring-boot-starter-data-redis）</li>
     *   <li>{@code sipproxy.cluster.sender-type=redis}</li>
     * </ul>
     *
     * @param connectionFactory Redis 连接工厂
     * @return RedisMessageListenerContainer 实例
     */
    @Bean
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "sipproxy.cluster", name = "sender-type", havingValue = "redis")
    public RedisMessageListenerContainer sipProxyRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * 注册基于 Redis pub/sub 的 WebSocket 广播发送器
     * <p>
     * 启用条件：{@code sipproxy.cluster.sender-type=redis}。
     * 通过 {@link RedisWsMessageSender#registerListener} 在 Bean 创建后订阅广播 channel。
     *
     * @param redisTemplate      Redis 操作模板
     * @param sipProxyProperties sipproxy 配置属性
     * @param listenerContainer  Redis 消息监听容器（可选，未配置时跳过监听器注册）
     * @return RedisWsMessageSender 实例
     */
    @Bean
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "sipproxy.cluster", name = "sender-type", havingValue = "redis")
    public RedisWsMessageSender redisWsMessageSender(StringRedisTemplate redisTemplate,
                                                     SipProxyProperties sipProxyProperties,
                                                     @Autowired(required = false) RedisMessageListenerContainer listenerContainer) {
        RedisWsMessageSender sender = new RedisWsMessageSender(redisTemplate, sipProxyProperties);
        // 容器存在时注册广播监听器，订阅 broadcast channel
        if (listenerContainer != null) {
            sender.registerListener(listenerContainer);
        }
        return sender;
    }

    // ==================== 消费者注册 ====================

    /**
     * 注册集群广播消费者
     * <p>
     * 启用条件：容器中同时存在 {@link WsMessageSender} 与 {@link WsSessionManager} Bean。
     * 通过 {@link ClusterBroadcastConsumer#init()} 注册接收回调，
     * 使本实例从 MQ/local 收到广播后能转发到本地 WebSocket 会话。
     *
     * @param wsMessageSender  WebSocket 消息广播发送器
     * @param wsSessionManager WebSocket 会话管理器
     * @return ClusterBroadcastConsumer 实例
     */
    @Bean
    @ConditionalOnBean({WsMessageSender.class, WsSessionManager.class})
    public ClusterBroadcastConsumer clusterBroadcastConsumer(WsMessageSender wsMessageSender,
                                                             WsSessionManager wsSessionManager) {
        ClusterBroadcastConsumer consumer = new ClusterBroadcastConsumer(wsMessageSender, wsSessionManager);
        // 注册接收回调：从 MQ/local 收到广播后转发到本实例会话
        consumer.init();
        return consumer;
    }
}
