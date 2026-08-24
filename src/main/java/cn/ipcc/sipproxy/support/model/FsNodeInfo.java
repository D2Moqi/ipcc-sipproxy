package cn.ipcc.sipproxy.support.model;

import cn.ipcc.sipproxy.support.SipProxyConstants;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * FS 节点信息数据模型
 * <p>
 * 由父程序实现 {@code cn.ipcc.sipproxy.api.fs.FsNodeProvider} 时填充并返回。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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

    /**
     * SIP 传输协议：1-UDP，2-TCP（非 2 一律按 UDP 处理）
     * <p>
     * 转发到 FS 的出站腿协议优先按此配置；未配置（null）时回退会话入站腿协议
     * （INVITE Via transport），保持存量节点向后兼容。
     */
    private Integer transportProtocol;

    /** ESL IP（sipproxy 不直接使用，预留用于父程序拦截器调用 FsClient） */
    private String eslIp;

    /** ESL 端口（通常 8021） */
    private Integer eslPort;

    /** 状态（0-禁用 1-启用，sipproxy 仅选用 status=1 的节点转发信令） */
    private Integer status;

    /**
     * 解析 FS 节点 SIP 传输协议字符串（tcp/udp）
     * <p>
     * 语义：transportProtocol 1=UDP（缺省），2=TCP，非 2 一律 UDP。
     * 转发到 FS 的出站腿（forwardToFreeSwitch/doForwardToFreeSwitch）统一经此方法取值，
     * 与 GatewayInfo.resolveSipTransport() 保持同源语义。
     */
    public String resolveSipTransport() {
        return Integer.valueOf(2).equals(transportProtocol)
                ? SipProxyConstants.TRANSPORT_TCP : SipProxyConstants.TRANSPORT_UDP;
    }
}
