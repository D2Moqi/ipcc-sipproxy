package cn.ipcc.sipproxy.support.model;

import lombok.Data;

/**
 * 坐席信息数据模型（sipproxy 自有，不依赖 cc-server）
 * <p>
 * 设计意图：作为 sipproxy 与父程序之间的数据载体，仅包含 sipproxy 实际使用的字段，
 * 避免直接暴露父程序的 SysAgentDO（含 ORM 注解、租户上下文等框架依赖）。
 * 字段基于原 SysAgentDO 在 sipproxy 中的实际使用情况定义。
 * <p>
 * 由父程序实现 {@code cn.ipcc.sipproxy.api.agent.AgentInfoProvider} 时填充并返回。
 */
@Data
public class AgentInfo {

    /** 分机号（坐席用户名，对应 SIP REGISTER 的 extension） */
    private String extension;

    /** 域名（SIP 注册域，可空表示不区分域名） */
    private String domain;

    /** 密码（明文或 MD5，由父程序决定校验方式，sipproxy 不解读其格式） */
    private String password;

    /** 坐席 ID（父程序主键，sipproxy 仅做透传，用于日志关联） */
    private Long agentId;

    /** 租户 ID（父程序多租户标识，sipproxy 不解析租户上下文） */
    private Long tenantId;

    /** 显示名称（用于 SIP From/To Display，可空） */
    private String displayName;
}
