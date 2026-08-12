package cn.ipcc.sipproxy.support;

/**
 * sipproxy 模块通用常量
 * <p>
 * 包含信令来源标识、JSSIP 标识、会话状态、呼叫类型、支持的 SIP 方法集合等常量。
 */
public interface SipProxyConstants {

    /** 信令来源：WebSocket（坐席端） */
    String WEBSOCKET = "WEBSOCKET";
    /** 信令来源：FreeSWITCH（FS 节点） */
    String FREESWITCH = "FREESWITCH";
    /** 信令来源：第三方网关 */
    String THIRD_PARTY = "THIRD_PARTY";

    /** IPCC_JSSIP 标识常量（用于识别 IPCC_JSSIP 客户端 User-Agent，前端 JsSIP 统一设置此值） */
    String IPCC_JSSIP = "IPCC_JSSIP";
    /**
     * FreeSWITCH User-Agent 标识常量
     * 设计意图: FreeSWITCH 默认 User-Agent 形如 "FreeSWITCH-mod_sofia/1.10.12-...",
     * 用于在 Response 来源识别时区分 FS 与第三方 SIP 服务(避免依赖 Via 头 received 参数,
     * 该参数在 Response 中记录的是请求路径上节点的 IP,无法直接反映响应发送方)
     */
    String FREESWITCH_USER_AGENT = "FREESWITCH";

    /** 会话状态：邀请中（INVITE 已发送，等待响应） */
    String SESSION_STATUS_INVITING = "INVITING";
    /** 会话状态：尝试中（收到 1xx 临时响应） */
    String SESSION_STATUS_TRYING = "TRYING";
    /** 会话状态：振铃中（收到 180 Ringing） */
    String SESSION_STATUS_RINGING = "RINGING";
    /** 会话状态：已建立（收到 200 OK + ACK） */
    String SESSION_STATUS_ESTABLISHED = "ESTABLISHED";
    /** 会话状态：失败（收到 4xx/5xx/6xx 错误响应） */
    String SESSION_STATUS_FAILED = "FAILED";
    /** 会话状态：已取消（收到 CANCEL 或发送 CANCEL） */
    String SESSION_STATUS_CANCELLED = "CANCELLED";
    /** 会话状态：已重定向（收到 3xx 响应） */
    String SESSION_STATUS_REDIRECTED = "REDIRECTED";
    /** 会话状态：已终止（收到 BYE + 200 OK） */
    String SESSION_STATUS_TERMINATED = "TERMINATED";

    /** 呼叫类型：内部呼叫（坐席→坐席） */
    String CALL_TYPE_INTERNAL = "INTERNAL";
    /** 呼叫类型：呼入（外部→坐席） */
    String CALL_TYPE_INBOUND = "INBOUND";
    /** 呼叫类型：呼出（坐席→外部） */
    String CALL_TYPE_OUTBOUND = "OUTBOUND";
    /** 呼叫类型：未知（无法识别来源） */
    String CALL_TYPE_UNKNOWN = "UNKNOWN";

    /** sipproxy 支持的 SIP 方法集合（用于 OPTIONS 响应的 Allow 头） */
    String SIP_METHODS_SUPPORTED = "INVITE, ACK, CANCEL, BYE, REGISTER, OPTIONS, PRACK, SUBSCRIBE, NOTIFY, PUBLISH, INFO, REFER, MESSAGE, UPDATE";

    /**
     * SipURI 传输参数名（RFC 3261 标准）
     * <p>
     * 设计意图：JAIN-SIP 1.2.1.4 的 {@code SipURI.setTransportParam} 会校验传输方法
     * 仅支持 udp/tcp/tls/sctp，不识别 RFC 7118 的 ws/wss。改用 {@code setParameter(TRANSPORT_PARAM, "ws")}
     * 绕过校验，wire 格式输出 {@code ;transport=ws} 完全一致。
     */
    String TRANSPORT_PARAM = "transport";

    // ===== JAIN-SIP 协议栈常量 =====
    /** JAIN-SIP 实现类路径(gov.nist 为标准参考实现) */
    String SIP_STACK_PATH = "gov.nist";
    /** SipStack 名称(仅供 JAIN-SIP 内部标识) */
    String STACK_NAME = "SipServiceStack";
    /** 禁用自动对话框支持(B2BUA 场景需手动管理对话) */
    String AUTOMATIC_DIALOG_SUPPORT_OFF = "off";
    /** 未知 SIP 方法标识(日志兜底用) */
    String UNKNOWN_METHOD = "unknown";

    // ===== SIP 传输协议常量 =====
    /** UDP 传输协议标识 */
    String TRANSPORT_UDP = "udp";
    /** TCP 传输协议标识 */
    String TRANSPORT_TCP = "tcp";

    // ===== SIP 响应状态码常量(JAIN-SIP 1.2 未提供 429 常量,此处自定义) =====
    /** 429 Too Many Requests(请求过多,触发限流时返回) */
    int STATUS_TOO_MANY_REQUESTS = 429;

    /**
     * 出局标记头名（问题16环路防护）
     * <p>
     * 出局 INVITE 经 DefaultOutboundGatewayRewriter 注入该头；若第三方网关将出局报文
     * （含透传的 X-头）路由回 proxy，入站方向检测到该标记即判定为 INVITE 环路，拒绝再次出局。
     */
    String HEADER_OUTBOUND_MARK = "X-IPCC-Outbound";

}
