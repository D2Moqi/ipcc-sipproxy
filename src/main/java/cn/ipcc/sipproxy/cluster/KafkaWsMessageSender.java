package cn.ipcc.sipproxy.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 基于 Kafka 的 WebSocket 集群广播实现（占位骨架）
 * <p>
 * 启用条件：
 * <ul>
 *   <li>类路径存在 {@code org.springframework.kafka.core.KafkaTemplate}（父程序引入 spring-kafka）</li>
 *   <li>{@code sipproxy.cluster.sender-type=kafka}</li>
 * </ul>
 * <p>
 * 当前为占位实现，具体逻辑待后续迁移阶段补充：
 * <ul>
 *   <li>send：通过 KafkaTemplate.send(topic, json) 发布消息到广播 topic</li>
 *   <li>onReceive：通过 @KafkaListener 监听广播 topic，反序列化后回调</li>
 * </ul>
 */
@Slf4j
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
@ConditionalOnProperty(prefix = "sipproxy.cluster", name = "sender-type", havingValue = "kafka")
public class KafkaWsMessageSender implements WsMessageSender {

    /**
     * 占位构造器
     * <p>
     * TODO 后续迁移阶段补充：
     * <ul>
     *   <li>注入 KafkaTemplate 用于消息发送</li>
     *   <li>注入 SipProxyProperties 用于读取 senderKafkaTopic 配置</li>
     *   <li>注入 ObjectMapper 用于 JSON 序列化</li>
     * </ul>
     */
    public KafkaWsMessageSender() {
        // TODO 实现 Kafka 广播发送与接收
    }

    @Override
    public void send(SipWsBroadcastMessage message) {
        // TODO 通过 KafkaTemplate.send(senderKafkaTopic, json) 发布广播消息
        log.warn("[send][KafkaWsMessageSender 占位实现,未实际发送] target={}", message.getTarget());
    }

    @Override
    public void onReceive(BroadcastReceiveCallback callback) {
        // TODO 通过 @KafkaListener 监听 senderKafkaTopic，反序列化后回调
        log.warn("[onReceive][KafkaWsMessageSender 占位实现,未注册监听器]");
    }
}
