package cn.ipcc.sipproxy.cluster;

import lombok.Data;

import java.io.Serializable;

/**
 * WebSocket 集群广播消息载体
 * <p>
 * 多实例部署时，本实例不持有目标 WebSocket 会话时，通过广播消息
 * 让目标实例接收并转发 SIP 消息到目标坐席。
 * <p>
 * 消息类型说明：
 * <ul>
 *   <li>{@code USER}：按 username:domain 路由，仅持有该坐席会话的实例转发</li>
 *   <li>{@code SESSION}：按 sessionId 路由，仅目标实例转发</li>
 *   <li>{@code ALL}：广播到所有实例的所有会话</li>
 * </ul>
 * <p>
 * 序列化约束：通过 Jackson 序列化为 JSON 在 MQ 通道中传输，
 * 字段需保持 POJO 风格（getter/setter），不可使用不可变记录类。
 */
@Data
public class SipWsBroadcastMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息类型：USER（按 username:domain 路由）/ ALL（广播）/ SESSION（按 sessionId 路由） */
    private String targetType;

    /** 目标标识（targetType=USER 时为 "username:domain"，SESSION 时为 sessionId） */
    private String target;

    /** SIP 消息文本 */
    private String message;

    /** 发送方实例 ID，避免循环广播（接收方忽略与自身 instanceId 相同的消息） */
    private String sourceInstance;

    /** 时间戳（毫秒），用于排查广播延迟 */
    private Long timestamp;
}
