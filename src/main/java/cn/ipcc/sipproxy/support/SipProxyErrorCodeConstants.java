package cn.ipcc.sipproxy.support;

/**
 * sipproxy 模块错误码常量
 * <p>
 * 错误码在 {@link SipProxyException} 中使用，由 sipproxy 内部抛出，父程序可通过捕获异常获取 code 做差异化处理。
 */
public final class SipProxyErrorCodeConstants {

    /** 内部服务错误（通用异常兜底） */
    public static final Integer INTERNAL_SERVER_ERROR = 500;
    /** 无可用 FS 节点（FsNodeProvider 返回空列表时抛出） */
    public static final Integer NO_AVAILABLE_FS_NODE = 501;
    /** 会话不存在（按 Call-ID / sessionId 查询会话失败） */
    public static final Integer SESSION_NOT_FOUND = 502;
    /** 网关不存在（按 ID / name 查询网关失败） */
    public static final Integer GATEWAY_NOT_FOUND = 503;
    /** 坐席不存在（按 extension 查询坐席失败） */
    public static final Integer AGENT_NOT_FOUND = 504;
    /** 网关类型无效（GatewayInfo.type 不在 GatewayTypeEnum 范围内） */
    public static final Integer GATEWAY_TYPE_INVALID = 505;
    /** 转发失败（SIP 消息转发到 FS/第三方时异常） */
    public static final Integer FORWARD_FAILED = 506;

    private SipProxyErrorCodeConstants() {
    }
}
