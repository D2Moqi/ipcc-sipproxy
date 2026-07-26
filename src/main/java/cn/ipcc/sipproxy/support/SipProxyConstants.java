package cn.ipcc.sipproxy.support;

/**
 * sipproxy 模块通用常量
 * <p>
 * 设计意图：从原 {@code cn.iocoder.yudao.module.cc.sipproxy.constant.SipProxyConstants} 拷贝至
 * sipproxy 模块，仅改包名，常量定义保持一致以确保迁移期间行为对齐。
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

    /** JSSIP 标识常量（用于识别 JsSIP 客户端 User-Agent） */
    String JSSIP = "JSSIP";

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

}
