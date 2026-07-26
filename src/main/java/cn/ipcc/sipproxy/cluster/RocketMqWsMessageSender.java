package cn.ipcc.sipproxy.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 基于 RocketMQ 的 WebSocket 集群广播实现（占位骨架）
 * <p>
 * 启用条件：
 * <ul>
 *   <li>类路径存在 {@code org.apache.rocketmq.spring.core.RocketMQTemplate}（父程序引入 rocketmq-spring-boot-starter）</li>
 *   <li>{@code sipproxy.cluster.sender-type=rocketmq}</li>
 * </ul>
 * <p>
 * 当前为占位实现，具体逻辑待后续迁移阶段补充：
 * <ul>
 *   <li>send：通过 RocketMQTemplate.syncSend(topic, json) 发布广播消息（需使用 MessageSelector 或广播消费模式）</li>
 *   <li>onReceive：通过 @RocketMQMessageListener 监听 senderRocketmqTopic（消费模式设为 BROADCASTING）</li>
 * </ul>
 */
@Slf4j
@ConditionalOnClass(name = "org.apache.rocketmq.spring.core.RocketMQTemplate")
@ConditionalOnProperty(prefix = "sipproxy.cluster", name = "sender-type", havingValue = "rocketmq")
public class RocketMqWsMessageSender implements WsMessageSender {

    /**
     * 占位构造器
     * <p>
     * TODO 后续迁移阶段补充：
     * <ul>
     *   <li>注入 RocketMQTemplate 用于消息发送</li>
     *   <li>注入 SipProxyProperties 用于读取 senderRocketmqTopic 配置</li>
     *   <li>注入 ObjectMapper 用于 JSON 序列化</li>
     * </ul>
     */
    public RocketMqWsMessageSender() {
        // TODO 实现 RocketMQ 广播发送与接收
    }

    @Override
    public void send(SipWsBroadcastMessage message) {
        // TODO 通过 RocketMQTemplate.syncSend(senderRocketmqTopic, json) 发布广播消息
        log.warn("[send][RocketMqWsMessageSender 占位实现,未实际发送] target={}", message.getTarget());
    }

    @Override
    public void onReceive(BroadcastReceiveCallback callback) {
        // TODO 通过 @RocketMQMessageListener 监听 senderRocketmqTopic，反序列化后回调
        log.warn("[onReceive][RocketMqWsMessageSender 占位实现,未注册监听器]");
    }
}
