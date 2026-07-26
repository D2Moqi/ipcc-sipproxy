package cn.ipcc.sipproxy.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 基于 RabbitMQ 的 WebSocket 集群广播实现（占位骨架）
 * <p>
 * 启用条件：
 * <ul>
 *   <li>类路径存在 {@code org.springframework.amqp.core.AmqpTemplate}（父程序引入 spring-rabbit）</li>
 *   <li>{@code sipproxy.cluster.sender-type=rabbitmq}</li>
 * </ul>
 * <p>
 * 当前为占位实现，具体逻辑待后续迁移阶段补充：
 * <ul>
 *   <li>send：通过 AmqpTemplate.convertAndSend(exchange, routingKey, json) 发布 fanout 消息</li>
 *   <li>onReceive：通过 @RabbitListener 监听匿名队列（绑定到 senderRabbitmqExchange）</li>
 * </ul>
 */
@Slf4j
@ConditionalOnClass(name = "org.springframework.amqp.core.AmqpTemplate")
@ConditionalOnProperty(prefix = "sipproxy.cluster", name = "sender-type", havingValue = "rabbitmq")
public class RabbitMqWsMessageSender implements WsMessageSender {

    /**
     * 占位构造器
     * <p>
     * TODO 后续迁移阶段补充：
     * <ul>
     *   <li>注入 AmqpTemplate 用于消息发送</li>
     *   <li>注入 SipProxyProperties 用于读取 senderRabbitmqExchange 配置</li>
     *   <li>注入 ObjectMapper 用于 JSON 序列化</li>
     * </ul>
     */
    public RabbitMqWsMessageSender() {
        // TODO 实现 RabbitMQ fanout 广播发送与接收
    }

    @Override
    public void send(SipWsBroadcastMessage message) {
        // TODO 通过 AmqpTemplate.convertAndSend(senderRabbitmqExchange, "", json) 发布 fanout 消息
        log.warn("[send][RabbitMqWsMessageSender 占位实现,未实际发送] target={}", message.getTarget());
    }

    @Override
    public void onReceive(BroadcastReceiveCallback callback) {
        // TODO 通过 @RabbitListener 监听匿名队列，反序列化后回调
        log.warn("[onReceive][RabbitMqWsMessageSender 占位实现,未注册监听器]");
    }
}
