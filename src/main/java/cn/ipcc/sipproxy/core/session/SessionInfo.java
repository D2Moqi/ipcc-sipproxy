package cn.ipcc.sipproxy.core.session;

import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import lombok.Data;

/**
 * SIP会话信息
 * 存储完整的会话信息，包括WebSocket会话、FreeSWITCH节点、第三方SIP服务节点等
 *
 * @author 芋道源码
 */
@Data
public class SessionInfo {

    /**
     * Call-ID
     */
    private String callId;

    /**
     * WebSocket会话ID
     */
    private String sessionId;

    /**
     * FreeSWITCH节点
     */
    private FsNodeInfo freeSwitchNode;

    /**
     * 第三方SIP服务节点
     */
    private FsNodeInfo thirdPartyNode;

    /**
     * 呼叫类型
     * INTERNAL: 内部呼叫（jssip A 呼叫 jssip B）
     * OUTBOUND: 外呼（JsSIP A 呼叫第三方SIP服务）
     * INBOUND: 第三方呼叫（第三方SIP服务呼叫 JsSIP A）
     */
    private String callType;

    /**
     * 发送给fs/第三方sip服务的传输协议类型（udp/tcp）（默认udp，在收到fs/第三方sip服务发送的invite时设置）
     * 根据INVITE请求Via头中的transport参数确定
     */
    private String toSipTransport;

    /**
     * WebSocket方的Contact头信息,name
     */
    private String websocketContactName;
    /**
     * WebSocket方的Contact头信息,ip
     */
    private String websocketContactIp;
    /**
     * WebSocket方的Contact头信息,port
     */
    private int websocketContactPort;
    /**
     * WebSocket方的Contact头信息,transport
     */
    private String websocketContactTransport;

    /**
     * 网关ID
     * 从INVITE请求头 X-Gateway-Id 中提取，用于标识呼叫来源网关
     */
    private String gatewayId;

    /**
     * 标记是否已对第三方网关执行过407鉴权重发
     * <p>
     * 业务背景：第三方网关对INVITE返回407 Proxy Authentication Required时，SIP代理需注入
     * Proxy-Authorization头并重发INVITE。为避免凭证错误导致407循环，重发次数上限1次。
     * <p>
     * 持久化策略：随SessionInfo的Jackson序列化一并存入Redis，确保跨请求/响应周期（INVITE发出 →
     * 407响应回调 → 重发 → 可能的二次407）的循环防护有效。无需独立Redis键。
     */
    private boolean authRetried = false;

    /**
     * 出局INVITE请求的原始文本内容缓存
     * <p>
     * 在forwardToOutboundGateway发送INVITE前缓存（已完成Via/Contact/Request-URI/From等信令改写），
     * 用于收到407 Proxy Authentication Required时解析重建Request对象，注入Proxy-Authorization头后重发，
     * 保留原始SDP offer和出局信令改写结果，避免重新构造INVITE丢失媒体信息。
     */
    private String originalInviteContent;


    public SessionInfo() {
    }

    public SessionInfo(String callId) {
        this();
        this.callId = callId;
    }

}
