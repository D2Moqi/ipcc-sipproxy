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

    /**
     * 入局第三方 INVITE 的原始顶层 Via 头缓存（不含 "Via: " 名称前缀）
     * <p>
     * 业务背景：呼入(INBOUND)场景下 200 OK/ringing 等响应回送第三方主叫时，
     * 必须遵循 RFC3581 发往原始请求顶层 Via 的 received:rport（NAT 穿透真实来源），
     * 而不能按第三方网关节点静态配置 address:port 发送——软电话等客户端实际注册端口
     * 常为随机端口（如 rport=61199），与网关静态配置端口（如 5060）不一致，
     * 按静态配置发送会导致 200 OK 丢失、主叫持续重传 INVITE 直至 408 超时。
     * <p>
     * 取值规则：第三方来源(INBOUND) INVITE 建会话时保存其顶层 Via 原文；
     * rport 缺失时由消费方回退 Via sent-by host:port（JAIN-SIP 按 Via 规则自然回退）。
     * <p>
     * 持久化策略：String 文本随 SessionInfo 序列化存入 Redis，跨请求/响应周期有效。
     */
    private String inboundTopVia;

    /**
     * 出局方向 FS INVITE 的原始顶层 Via 头缓存（不含 "Via: " 名称前缀）
     * <p>
     * 业务背景：出局(OUTBOUND)场景下 CC FS 发起的出局 INVITE 经代理转发到第三方网关后，
     * 第三方返回的 200 OK/ringing/4xx 等响应必须遵循 RFC3581 发往原始请求顶层 Via 的
     * received:rport（即 CC FS 实际发送端口，如 15580/16580），否则 CC FS 收不到任何响应，
     * 持续重传 INVITE 直至 Timer B 超时 408。
     * <p>
     * 取值规则：FS 来源(OUTBOUND/INTERNAL) INVITE 建会话时保存其顶层 Via 原文；
     * 响应回送 FS 时还原该 Via 后直接发送，JAIN-SIP 按 received:rport 准确投递。
     * <p>
     * 持久化策略：String 文本随 SessionInfo 序列化存入 Redis，跨请求/响应周期有效。
     */
    private String outboundFsTopVia;

    /**
     * 出局方向 FS INVITE 的原始 CSeq 序号缓存
     * <p>
     * 业务背景：出局(OUTBOUND)场景 CC FS 发起的 INVITE 若触发第三方网关 407 挑战，
     * 代理按 RFC3261 §22.2 重发带 Digest 凭据的 INVITE，新事务必须递增 CSeq。
     * 网关对重发请求返回的 200 OK 携带递增后的 CSeq（如 118629022），若原样回送 CC FS，
     * 其 sofia 因 CSeq 与自身 INVITE（如 118629021）不一致无法关联事务，
     * 丢弃 200 OK → 无 ACK → Timer B 超时 408。回送 FS 前必须还原为原始 CSeq。
     * <p>
     * 取值规则：FS 来源(OUTBOUND/INTERNAL) INVITE 建会话时保存其 CSeq 序号。
     * <p>
     * 持久化策略：随 SessionInfo 序列化存入 Redis，跨请求/响应周期有效。
     */
    private Long outboundFsCSeq;

    /**
     * 入局 in-dialog 请求（INFO/BYE/UPDATE 等非 INVITE 方法）的顶层 Via 缓存：CSeq 序号 → Via 原文
     * <p>
     * 业务背景：第三方网关（如注册型 4G 网关/模拟网关）在通话期间可能发起 INFO 等
     * in-dialog 请求，其响应回送时必须携带<b>该请求自身事务的顶层 Via</b>（各请求
     * 拥有独立 branch），否则网关 sofia 按 branch 无法关联事务，丢弃 200 OK 后持续
     * 重传（风暴阻塞后续 BYE 发送，坐席侧挂断联动延迟数十秒）。
     * <p>
     * 取值规则：转发 in-dialog 请求到 FS 前，按请求 CSeq 序号缓存其顶层 Via 原文；
     * 响应回送第三方时按响应 CSeq 序号查表还原（INVITE 事务响应仍走 inboundTopVia）。
     * <p>
     * 持久化策略：随 SessionInfo 序列化存入 Redis，跨请求/响应周期有效；
     * 缓存随会话清理，无需独立 TTL。
     */
    private java.util.Map<Long, String> inboundDialogTopViaByCSeq = new java.util.HashMap<>();

    /** 入局 in-dialog Via 缓存容量上限：长通话 + 高频 INFO（DTMF 上报等）下防止 map 无界增长与随会话序列化的写放大 */
    private static final int MAX_INBOUND_DIALOG_VIA_CACHE = 50;

    /**
     * 缓存入局 in-dialog 请求的顶层 Via（按 CSeq 序号索引）
     * <p>
     * 容量约束：超出 {@link #MAX_INBOUND_DIALOG_VIA_CACHE} 时移除最小 CSeq 条目
     * （最旧请求的 Via，其响应早已回送，保留无意义），避免长通话下 map 无限膨胀。
     *
     * @param cseq   CSeq 序号（同一事务重传 CSeq 不变，覆盖写入）
     * @param via    该请求的顶层 Via 原文（不含 "Via: " 前缀）
     */
    public void cacheInboundDialogTopVia(Long cseq, String via) {
        if (cseq != null && via != null && !via.isEmpty()) {
            this.inboundDialogTopViaByCSeq.put(cseq, via);
            if (this.inboundDialogTopViaByCSeq.size() > MAX_INBOUND_DIALOG_VIA_CACHE) {
                Long oldestCseq = null;
                for (Long cachedCseq : this.inboundDialogTopViaByCSeq.keySet()) {
                    if (oldestCseq == null || cachedCseq < oldestCseq) {
                        oldestCseq = cachedCseq;
                    }
                }
                if (oldestCseq != null) {
                    this.inboundDialogTopViaByCSeq.remove(oldestCseq);
                }
            }
        }
    }

    /**
     * 按 CSeq 序号查询入局 in-dialog 请求的顶层 Via
     *
     * @param cseq CSeq 序号
     * @return 顶层 Via 原文；未缓存返回 null
     */
    public String getInboundDialogTopVia(Long cseq) {
        return cseq != null ? this.inboundDialogTopViaByCSeq.get(cseq) : null;
    }

    public java.util.Map<Long, String> getInboundDialogTopViaByCSeq() {
        return inboundDialogTopViaByCSeq;
    }

    public void setInboundDialogTopViaByCSeq(java.util.Map<Long, String> inboundDialogTopViaByCSeq) {
        this.inboundDialogTopViaByCSeq = inboundDialogTopViaByCSeq;
    }


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
