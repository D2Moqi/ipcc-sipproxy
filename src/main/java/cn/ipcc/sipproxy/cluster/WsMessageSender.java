package cn.ipcc.sipproxy.cluster;

/**
 * WebSocket 消息广播发送器抽象
 * <p>
 * 5 种实现：local / redis / rocketmq / rabbitmq / kafka。
 * 由 {@code SipProxyClusterAutoConfiguration} 按配置
 * {@code sipproxy.cluster.sender-type} 选择实现。
 * <p>
 * 设计意图：解耦消息发送与底层 MQ 中间件，上层调用方无需感知广播实现细节。
 */
public interface WsMessageSender {

    /**
     * 发送广播消息
     *
     * @param message 广播消息载体
     */
    void send(SipWsBroadcastMessage message);

    /**
     * 注册接收广播消息回调
     * <p>
     * 由 {@code ClusterBroadcastConsumer} 在初始化时调用，
     * 当本实例从 MQ 通道收到广播消息时触发回调。
     *
     * @param callback 接收回调
     */
    void onReceive(BroadcastReceiveCallback callback);

    /**
     * 接收回调函数式接口
     */
    @FunctionalInterface
    interface BroadcastReceiveCallback {

        /**
         * 接收集群广播消息
         *
         * @param message 广播消息载体
         */
        void onReceive(SipWsBroadcastMessage message);
    }
}
