package cn.ipcc.sipproxy.core.session;

import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * SIP会话信息
 * 存储完整的会话信息，包括WebSocket会话、FreeSWITCH节点、第三方SIP服务节点等
 * <p>
 * 会话持久化：所有字段随SessionInfo通过Jackson序列化存入Redis，TTL覆盖完整呼叫生命周期，
 * 407鉴权相关字段（authChallengeCount、last407Nonce）无需独立Redis键，随会话一并存储。
 *
 * @author ipcc
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
    private GatewayInfo thirdPartyNode;

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
     * 从INVITE请求头 X-Gateway-Id 中提取，用于标识呼叫来源网关或指定出局网关
     */
    private String gatewayId;

    /**
     * 407鉴权挑战计数（替代原authRetried boolean，支持stale=true重挑战场景）
     * <p>
     * 业务背景：原authRetried仅支持一次重试，无法区分stale=true（nonce过期，可安全重试）
     * 和stale=false（凭证错误，不应重试）两种场景，导致凭证错误时也可能无意义重试。
     * <p>
     * 取值规则：
     * - 0：未发起过407重试，首次收到407可正常重试
     * - 1：已重试1次，仅当stale=true且nonce更新时允许二次重试
     * - >=2：达到上限（MAX_AUTH_CHALLENGE_COUNT=2），不再重试
     * <p>
     * 持久化策略：随SessionInfo序列化入Redis，跨请求/响应周期有效。
     */
    private int authChallengeCount = 0;

    /**
     * 上一次407挑战的nonce值，用于检测stale=true重挑战（nonce是否更新）
     * <p>
     * 业务背景：当网关返回stale=true表示nonce过期时，客户端应使用新nonce重新计算Digest；
     * 通过比较本次407的nonce与last407Nonce，确认nonce确实更新后才允许二次重试，
     * 避免stale=false（凭证错误）场景下无意义重试。
     */
    private String last407Nonce;

    /**
     * 标记是否已对第三方网关执行过407鉴权重发
     *
     * @deprecated 已由 {@link #authChallengeCount} 替代，过渡期保留用于JSON反序列化兼容，
     * 读取时若authRetried=true则设置authChallengeCount=1。
     */
    @Deprecated
    private boolean authRetried = false;

    /**
     * 出局INVITE请求的原始文本缓存（最终转发前的完整SIP文本）
     * <p>
     * 业务背景：收到407 Proxy Authentication Required时，需要重新构造INVITE请求
     * 并注入Proxy-Authorization头。保存modifyHeadersForForwarding完成后、发送前
     * 的完整SIP文本，可通过SipAnalysisUtil.parseSipMessageRequest()正确还原Request对象。
     * <p>
     * 持久化策略：String文本随SessionInfo序列化存入Redis，跨请求/响应周期有效。
     * <p>
     * 触发时机：在forwardToThirdParty发送INVITE前缓存（已完成全部信令改写），
     * 用于407鉴权时还原原始INVITE，注入Proxy-Authorization头后重发。
     */
    private String originalInviteText;


    public SessionInfo() {
    }

    public SessionInfo(String callId) {
        this();
        this.callId = callId;
    }

    /**
     * 兼容旧authRetried字段反序列化：若旧数据authRetried=true且authChallengeCount=0，
     * 则设置authChallengeCount=1，确保循环防护逻辑正确。
     */
    public void setAuthRetried(boolean authRetried) {
        this.authRetried = authRetried;
        if (authRetried && this.authChallengeCount == 0) {
            this.authChallengeCount = 1;
        }
    }
}
