package cn.ipcc.sipproxy.support.model;

import lombok.Data;

/**
 * FS 节点信息数据模型
 * <p>
 * 由父程序实现 {@code cn.ipcc.sipproxy.api.fs.FsNodeProvider} 时填充并返回。
 */
@Data
public class FsNodeInfo {

    /** 节点 ID（父程序主键，sipproxy 仅做透传） */
    private Long id;

    /** 节点名称（用于日志展示，如 "fs-master-01"） */
    private String name;

    /** SIP 信令 IP（sipproxy 转发 SIP 消息的目标地址） */
    private String sipIp;

    /** SIP 信令端口（通常 5060） */
    private Integer sipPort;

    /** ESL IP（sipproxy 不直接使用，预留用于父程序拦截器调用 FsClient） */
    private String eslIp;

    /** ESL 端口（通常 8021） */
    private Integer eslPort;

    /** 状态（0-禁用 1-启用，sipproxy 仅选用 status=1 的节点转发信令） */
    private Integer status;
}
