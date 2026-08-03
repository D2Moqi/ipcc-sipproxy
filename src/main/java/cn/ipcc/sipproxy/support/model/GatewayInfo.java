package cn.ipcc.sipproxy.support.model;

import lombok.Data;

/**
 * 网关信息数据模型
 * <p>
 * 设计意图：出局信令改写与来源识别
 * 用于：
 * <ul>
 *   <li>{@code SipMessageForwarder.forwardToOutboundGateway} 出局 INVITE 改写</li>
 *   <li>{@code SipNodeManager.selectThirdPartyNode} 按 address:port 反查节点</li>
 * </ul>
 * <p>
 * 由父程序实现 {@code cn.ipcc.sipproxy.api.gateway.GatewayProvider} 时填充并返回。
 */
@Data
public class GatewayInfo {

    /** 网关 ID（字符串形式，对应父程序主键 toString） */
    private String id;

    /** 网关名称（唯一标识，用于按名称查询） */
    private String name;

    /** 网关地址（IP 或域名，出局 INVITE 的 target host） */
    private String address;

    /** 网关端口（出局 INVITE 的 target port） */
    private Integer port;

    /** 外部线路号码（fromUser / DID，出局 INVITE 的 From/User-Agent 改写值） */
    private String externalLineNumber;

    /** From 域名（出局 INVITE 的 From header domain） */
    private String fromDomain;

    /**
     * Caller-ID-In-From 标志（0=在 From 头使用原始主叫号码，1=使用 DID/externalLineNumber）
     * <p>
     * 业务背景：出局 INVITE 信令改写时，需根据此标志决定 From 头使用主叫原始号码还是网关的外部线路号码。
     */
    private Integer callerIdInFrom;

    /**
     * 认证类型：0-不认证（IP 型），1-认证（账号密码型）
     * <p>
     * 业务背景：出局 INVITE 信令改写时，若 authType=1 且配置了 username/password，
     * 需走 407 Digest 鉴权流程。
     */
    private Integer authType;

    /** SIP传输协议：1-UDP，2-TCP（INVITE 发送与 407 鉴权重发均使用此字段） */
    private Integer transportProtocol;

    /** 认证地址（预留字段，可为空；当前 INVITE 407 鉴权流程不使用，后续 REGISTER 场景可用） */
    private String authAddress;

    /** 认证端口（预留字段，可为空；说明同上） */
    private Integer authPort;

    /** 用户名（认证型网关鉴权账号） */
    private String username;

    /** 密码（认证型网关鉴权密码） */
    private String password;

    /** 重试时间（秒，可选） */
    private Integer retrySeconds;

    /** 心跳时间（秒，可选） */
    private Integer pingSeconds;

    /** 超时时间（秒）— SIP Timer B */
    private Integer expireSeconds;

    /** 状态：0-启用，1-禁用；按 ID 查询后必须校验 status=0 方可用于出局 */
    private Integer status;

}
