package cn.ipcc.sipproxy.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * sipproxy 模块配置属性
 * <p>
 * 设计意图：替代原 {@code CcConfig.SipProxyConfig}，使用独立配置前缀 {@code sipproxy.*}
 * 与父程序 {@code cc.*} 隔离，确保 sipproxy 可被任意非 yudao 工程复用。
 * <p>
 * 配置项对应 application.yaml 中的 {@code sipproxy} 配置块，包含以下子节点：
 * <ul>
 *   <li>{@code sip}：SIP 协议栈监听参数</li>
 *   <li>{@code websocket}：SIP over WebSocket 接入参数</li>
 *   <li>{@code heartbeat}：心跳机制配置（v1.2 新增）</li>
 *   <li>{@code cluster}：WS 集群广播配置</li>
 *   <li>{@code session}：会话存储 Redis 配置</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "sipproxy")
public class SipProxyProperties {

    /** 是否启用 sipproxy（默认 true，false 时自动配置不生效） */
    private boolean enabled = true;

    /**
     * 实例 ID（v1.2 新增）
     * <p>
     * 用于集群广播与日志追踪，多实例部署时通过不同 instance-id 区分。
     * 推荐 yaml 配置为 {@code ${HOSTNAME:node1}} 以读取 HOSTNAME 环境变量，
     * 未设置环境变量时回退为 "node1"。
     */
    private String instanceId = "node1";

    /** SIP 协议栈监听参数 */
    private Sip sip = new Sip();

    /** SIP over WebSocket 接入参数 */
    private Websocket websocket = new Websocket();

    /** 心跳机制配置（v1.2 新增） */
    private Heartbeat heartbeat = new Heartbeat();

    /** WS 集群广播配置 */
    private Cluster cluster = new Cluster();

    /** 会话存储 Redis 配置 */
    private Session session = new Session();

    /**
     * SIP 协议栈监听参数
     * <p>
     * 对应 JAIN-SIP SipStack 的监听端口与绑定地址，以及对外暴露的公网地址。
     */
    @Data
    public static class Sip {

        /** SIP 栈 UDP/TCP 监听端口（默认 5561，与原 cc.sip.port 保持一致） */
        private Integer port = 5561;

        /** SIP 栈绑定地址（默认 0.0.0.0，监听所有网卡） */
        private String bindAddress = "0.0.0.0";

        /** SIP 代理公网 IP（用于 Contact/Via 头改写，使外部能回信到此节点） */
        private String publicIp;

        /** SIP 代理公网端口（默认 5561，通常与 port 一致） */
        private Integer publicPort = 5561;
    }

    /**
     * SIP over WebSocket 接入参数
     * <p>
     * 定义 sipproxy 独立的 SIP WS endpoint，与业务 WS（{@code /cc/ws}）隔离。
     */
    @Data
    public static class Websocket {

        /** 是否启用 SIP over WebSocket（默认 true） */
        private boolean enabled = true;

        /** SIP WS endpoint 路径（默认 /sipproxy/ws，与 /cc/ws 隔离） */
        private String path = "/sipproxy/ws";

        /** 是否强制 WS 握手认证（默认 true，false 时跳过 token 校验，仅用于本地调试） */
        private boolean requireAuth = true;

        /** token 提取方式（默认 query，从 URL 参数提取；预留 header 方式扩展） */
        private String authTokenSource = "query";

        /** URL 参数名（默认 token，前端通过 ?token=xxx 传递） */
        private String tokenQueryParam = "token";
    }

    /**
     * 心跳机制配置（v1.2 新增）
     * <p>
     * 包含 SIP OPTIONS 心跳响应、僵尸会话清理两部分，详见迁移方案第十三章。
     */
    @Data
    public static class Heartbeat {

        /** 是否启用 OPTIONS 心跳响应（默认 true，JsSIP 客户端会定时发送 OPTIONS 保活） */
        private boolean optionsEnabled = true;

        /** OPTIONS 响应 Allow 头声明支持的方法集合（默认全量 SIP 方法） */
        private String optionsAllowMethods =
                "INVITE, ACK, CANCEL, BYE, REGISTER, OPTIONS, PRACK, SUBSCRIBE, NOTIFY, PUBLISH, INFO, REFER, MESSAGE, UPDATE";

        /** 心跳超时（秒，默认 90）：超过此时间未收到任何 SIP 消息的 WS 连接视为僵尸连接 */
        private Integer idleTimeout = 90;

        /** 是否启用僵尸会话清理（默认 true，定时清理 idleTimeout 内无活动的 WS 会话） */
        private boolean zombieCleanEnabled = true;
    }

    /**
     * WS 集群广播配置（v1.2 新增 instance-id 集成）
     * <p>
     * 多实例部署时通过广播将 WS 消息转发到目标实例，确保坐席连接在任意实例都能收到 SIP 消息。
     * sender-type 决定使用哪种 MQ 实现广播。
     */
    @Data
    public static class Cluster {

        /** 广播类型：local|redis|rocketmq|rabbitmq|kafka（默认 local 单实例） */
        private String senderType = "local";

        /** Redis 广播 channel（默认 ipcc:sipproxy:ws:broadcast，统一 ipcc:sipproxy: 命名空间） */
        private String senderRedisChannel = "ipcc:sipproxy:ws:broadcast";

        /** RocketMQ 广播 topic（sender-type=rocketmq 时使用） */
        private String senderRocketmqTopic;

        /** Kafka 广播 topic（sender-type=kafka 时使用） */
        private String senderKafkaTopic;

        /** RabbitMQ 广播 exchange（sender-type=rabbitmq 时使用） */
        private String senderRabbitmqExchange;
    }

    /**
     * 会话存储 Redis 配置
     * <p>
     * 定义会话信息与注册信息的 Redis Key 前缀与 TTL，与 {@code RedisConstants} 对齐。
     */
    @Data
    public static class Session {

        /** Redis Key 前缀（默认 ipcc:sipproxy:session:，与代码常量对齐） */
        private String redisKeyPrefix = "ipcc:sipproxy:session:";

        /** 会话 TTL（秒，默认 120，对应 RedisConstants.REFRESH_TIME） */
        private Integer sessionTtl = 120;

        /** 注册信息 TTL（秒，默认 3600，对应 RedisConstants.REGISTER_REFRESH_TIME） */
        private Integer registerTtl = 3600;
    }
}
