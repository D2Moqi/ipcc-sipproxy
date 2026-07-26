package cn.ipcc.sipproxy.cluster;

import cn.ipcc.sipproxy.autoconfigure.SipProxyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * 基于 Redis pub/sub 的 WebSocket 集群广播实现
 * <p>
 * 启用条件：{@code sipproxy.cluster.sender-type=redis}。
 * <p>
 * 设计依据（迁移方案第 14.2.3 节）：
 * <ul>
 *   <li>channel 名取自 {@code sipproxy.cluster.sender-redis-channel}（默认 ipcc:sipproxy:ws:broadcast）</li>
 *   <li>消息体通过 Jackson 序列化为 JSON，便于跨实例反序列化</li>
 *   <li>接收方忽略 {@code sourceInstance} 与自身 instanceId 相同的消息，避免循环广播</li>
 * </ul>
 */
@Slf4j
public class RedisWsMessageSender implements WsMessageSender {

    /** Redis 操作模板（String 类型，便于 JSON 文本收发） */
    private final StringRedisTemplate redisTemplate;

    /** sipproxy 配置属性 */
    private final SipProxyProperties sipProxyProperties;

    /** Jackson 序列化器（无配置直接使用默认 ObjectMapper） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 接收回调，由 ClusterBroadcastConsumer 通过 onReceive 注册 */
    private BroadcastReceiveCallback callback;

    /**
     * 构造 Redis 广播发送器
     *
     * @param redisTemplate       Redis 操作模板
     * @param sipProxyProperties  sipproxy 配置属性
     */
    public RedisWsMessageSender(StringRedisTemplate redisTemplate,
                                SipProxyProperties sipProxyProperties) {
        this.redisTemplate = redisTemplate;
        this.sipProxyProperties = sipProxyProperties;
    }

    /**
     * 注册 Redis 监听器
     * <p>
     * 由 {@code SipProxyClusterAutoConfiguration} 在容器启动时调用，订阅 broadcast channel。
     * 通过 {@link MessageListenerAdapter} 反射调用 {@link #onMessage(String, String)} 方法。
     *
     * @param container Spring Data Redis 提供的消息监听容器
     */
    public void registerListener(RedisMessageListenerContainer container) {
        String channel = sipProxyProperties.getCluster().getSenderRedisChannel();
        MessageListenerAdapter listenerAdapter = new MessageListenerAdapter(this, "onMessage");
        container.addMessageListener(listenerAdapter, new ChannelTopic(channel));
        log.info("[registerListener][已注册 Redis 广播监听器] channel={}", channel);
    }

    /**
     * Redis 消息回调
     * <p>
     * 被 {@link MessageListenerAdapter} 反射调用，参数顺序为 (message, pattern)。
     * 反序列化 JSON 后忽略自身发出的广播，再回调 {@link BroadcastReceiveCallback}。
     *
     * @param message Redis 推送的消息文本（JSON 格式）
     * @param pattern 订阅 pattern（topic 模式订阅时使用，此处为 null）
     */
    public void onMessage(String message, String pattern) {
        try {
            SipWsBroadcastMessage broadcast = objectMapper.readValue(message, SipWsBroadcastMessage.class);
            // 忽略自己发出的广播，避免循环
            if (sipProxyProperties.getInstanceId().equals(broadcast.getSourceInstance())) {
                return;
            }
            if (callback != null) {
                callback.onReceive(broadcast);
            }
        } catch (Exception e) {
            log.error("[onMessage][解析广播消息失败] message={}", message, e);
        }
    }

    @Override
    public void send(SipWsBroadcastMessage message) {
        try {
            message.setSourceInstance(sipProxyProperties.getInstanceId());
            message.setTimestamp(System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(message);
            String channel = sipProxyProperties.getCluster().getSenderRedisChannel();
            redisTemplate.convertAndSend(channel, json);
            log.debug("[send][已发布 Redis 广播消息] channel={}, target={}", channel, message.getTarget());
        } catch (Exception e) {
            log.error("[send][发布 Redis 广播消息失败] target={}", message.getTarget(), e);
        }
    }

    @Override
    public void onReceive(BroadcastReceiveCallback callback) {
        this.callback = callback;
    }
}
