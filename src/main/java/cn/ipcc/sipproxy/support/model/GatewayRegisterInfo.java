package cn.ipcc.sipproxy.support.model;

import lombok.Data;

/**
 * 网关注册绑定模型
 * <p>
 * 设计意图：记录 4G 网关（SIP UA）向 sipproxy REGISTER 成功后学习到的可达地址与有效期，
 * 供呼出目标解析（注册 Contact > 静态配置）、呼入来源识别与管理后台注册状态展示使用。
 * 数据持久化在 Redis（GATEWAY_REGISTER_PREFIX），由 GatewayRegistry 读写。
 *
 * @author ipcc
 */
@Data
public class GatewayRegisterInfo {

    /** 网关 ID（对应 cc_sipproxy_gateway.id） */
    private Long gatewayId;

    /** 注册账号（= cc_sipproxy_gateway.username） */
    private String username;

    /** 注册 Contact host（网关真实可达 IP，学习自 REGISTER Contact 头，缺省回退源 IP） */
    private String contactIp;

    /** 注册 Contact port（网关 SIP 监听端口，缺省 5060） */
    private Integer contactPort;

    /** 传输协议（udp/tcp，取 Contact 的 transport 参数，缺省 udp） */
    private String transport;

    /** 注册过期时间戳（毫秒） */
    private Long expiresAt;

    /** REGISTER 报文来源 IP（辅助识别与 NAT 兜底） */
    private String sourceIp;

    /** 网关 UA（日志/识别辅助） */
    private String userAgent;
}