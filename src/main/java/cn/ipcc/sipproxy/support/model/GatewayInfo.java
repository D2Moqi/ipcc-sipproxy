package cn.ipcc.sipproxy.support.model;

import lombok.Data;

/**
 * 网关信息数据模型（sipproxy 自有，不依赖 cc-server）
 * <p>
 * 设计意图：替代原 FsSipGatewayDO 在 sipproxy 中的使用场景，仅保留出局信令改写与来源识别所需字段，
 * 避免 sipproxy 直接依赖父程序 ORM 实体。用于：
 * <ul>
 *   <li>{@code SipMessageForwarder.forwardToOutboundGateway} 出局 INVITE 改写</li>
 *   <li>{@code SipNodeManager.selectThirdPartyNode} 按 externalIp 反查节点</li>
 *   <li>{@code GatewayRouteServiceImpl} 路由选择</li>
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

    /** 网关类型（1-注册型 2-IP 型，对应 {@code cn.ipcc.sipproxy.support.GatewayTypeEnum}） */
    private Integer type;

    /** 代理地址（出局 INVITE 的 target，格式 host:port） */
    private String proxy;

    /** 外部线路号码（fromUser / DID，出局 INVITE 的 From/User-Agent 改写值） */
    private String externalLineNumber;

    /** From 域名（出局 INVITE 的 From header domain） */
    private String fromDomain;

    /**
     * Caller-ID-In-From 标志（0=在 From 头使用原始主叫号码，非 0=使用 DID/externalLineNumber）
     * <p>
     * 业务背景：出局 INVITE 信令改写时，需根据此标志决定 From 头使用主叫原始号码还是网关的外部线路号码。
     * 对应原 FsSipGatewayDO.callerIdInFrom 字段，迁移时保留以维持原有业务逻辑。
     */
    private Integer callerIdInFrom;

    /**
     * 是否需要注册（1=注册型网关，需注入 Authorization 头；0/空=IP 型网关，无需鉴权）
     * <p>
     * 业务背景：出局 INVITE 信令改写时，若 register=1 且配置了 userName/password，
     * 需注入 Authorization 头供网关鉴权。对应原 FsSipGatewayDO.register 字段。
     */
    private Integer register;

    /** Realm（认证域，注册型网关鉴权使用） */
    private String realm;

    /** 用户名（注册型网关鉴权账号） */
    private String username;

    /** 密码（注册型网关鉴权密码） */
    private String password;

    /** 注册 IP（用于按来源 IP 反查网关，识别 THIRD_PARTY 来源） */
    private String externalIp;

    /** Timer B（毫秒，可选；超出此时间未收到 1xx/2xx 响应则视为事务失败） */
    private Long timerB;
}
