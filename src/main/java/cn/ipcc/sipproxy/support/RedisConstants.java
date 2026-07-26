package cn.ipcc.sipproxy.support;

/**
 * sipproxy 模块 Redis Key 常量
 * <p>
 * 命名规范：{@code ipcc:sipproxy:<category>:<sub-key>}
 * <ul>
 *   <li>{@code ipcc:} 顶层命名空间，对应 groupId {@code cn.ipcc}，确保全局唯一</li>
 *   <li>{@code sipproxy:} 模块命名空间，对应 artifactId {@code ipcc-sipproxy}，确保模块隔离</li>
 *   <li>{@code <category>:} 业务类别，与代码包结构对齐（session / user / message）</li>
 * </ul>
 * <p>
 * v1.2 修订：旧前缀 {@code sip:*} 改为 {@code ipcc:sipproxy:*}，避免与其他 SIP 相关模块冲突，
 * 并与 {@code sipproxy.session.redis-key-prefix} 配置项对齐。
 */
public final class RedisConstants {

    /** 会话信息缓存刷新时间（秒），默认 120（一次性会话数据，TTL 无需过长） */
    public static final Integer REFRESH_TIME = 120;

    /**
     * REGISTER 注册信息缓存刷新时间（秒），默认 3600（1 小时）
     * <p>
     * 设计依据：JsSIP 默认 REGISTER Expires=1800 秒（30 分钟），TTL 必须 > Expires，
     * 否则会出现"WebSocket 连接存活但 Redis 缓存已过期"导致 forwardToWebSocketByUser 找不到会话。
     * WebSocket 异常断开时由 cleanupRegisterInfo 清理。
     */
    public static final Integer REGISTER_REFRESH_TIME = 3600;

    /** SIP 消息记录缓存有效期（分钟），默认 720（12 小时） */
    public static final Integer EXPIRATION = 720;

    /** Call-ID → SessionInfo JSON（TTL: REFRESH_TIME） */
    public static final String SESSION_INFO_PREFIX = "ipcc:sipproxy:session:info:";
    /** sessionId → 注册信息（TTL: REGISTER_REFRESH_TIME） */
    public static final String SESSION_REGISTER_MAPPING_PREFIX = "ipcc:sipproxy:session:register:";
    /** username:domain → sessionId（TTL: REGISTER_REFRESH_TIME） */
    public static final String USER_SESSION_MAPPING_PREFIX = "ipcc:sipproxy:user:session:";
    /** SIP 消息流水记录（TTL: EXPIRATION，单位分钟） */
    public static final String SIP_MESSAGE_RECORD_PREFIX = "ipcc:sipproxy:message:record:";
    /** Call-ID → FsNodeInfo JSON（TTL: REFRESH_TIME） */
    public static final String SESSION_NODE_MAPPING_PREFIX = "ipcc:sipproxy:session:fsnode:";
    /** Call-ID → 第三方节点信息（TTL: REFRESH_TIME） */
    public static final String SESSION_THIRD_PARTY_MAPPING_PREFIX = "ipcc:sipproxy:session:thirdparty:";

    private RedisConstants() {
    }
}
