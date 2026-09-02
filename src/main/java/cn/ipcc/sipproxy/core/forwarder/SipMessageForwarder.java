package cn.ipcc.sipproxy.core.forwarder;

import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.api.gateway.OutboundGatewayRewriter;
import cn.ipcc.sipproxy.api.media.SdpProcessor;
import cn.hutool.core.util.StrUtil;
import cn.ipcc.sipproxy.autoconfigure.SipProxyProperties;
import cn.ipcc.sipproxy.core.auth.GatewayAuthManager;
import cn.ipcc.sipproxy.core.node.SipNodeManager;
import cn.ipcc.sipproxy.core.register.GatewayRegistry;
import cn.ipcc.sipproxy.core.session.FsInboundChannelRegistry;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.session.SipSessionManager;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.SipProxyErrorCodeConstants;
import cn.ipcc.sipproxy.support.SipProxyException;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import cn.ipcc.sipproxy.support.model.GatewayRegisterInfo;
import cn.ipcc.sipproxy.websocket.WsSessionManager;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.SipProvider;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SIP消息转发器
 * 负责将SIP消息转发到不同的目标（WebSocket、FreeSWITCH、第三方SIP）
 *
 * @author ipcc
 */
@Slf4j
@Component
public class SipMessageForwarder {

    @Resource
    private SipSessionManager sessionManager;

    @Resource
    private WsSessionManager wsSessionManager;

    @Resource
    private SipNodeManager nodeManager;

    @Resource
    private GatewayProvider gatewayProvider;

    @Resource
    private GatewayAuthManager gatewayAuthManager;

    /**
     * FS 入站连接注册表(INVITE 到达时缓存的入站 MessageChannel,响应沿该连接原路回送)
     */
    @Resource
    private FsInboundChannelRegistry inboundChannelRegistry;

    @Resource
    private OutboundGatewayRewriter outboundGatewayRewriter;

    @Resource
    private GatewayRegistry gatewayRegistry;

    @Resource
    private SdpProcessor sdpProcessor;

    @Resource
    private SipProxyProperties properties;

    @Setter
    private SipProvider sipProvider;
    @Setter
    private SipProvider sipProviderTcp;
    @Setter
    private HeaderFactory headerFactory;
    @Setter
    private AddressFactory addressFactory;
    @Setter
    private String localIpAddress;
    @Setter
    private int sipPort;
    /**
     * JAIN-SIP 协议栈实例(无状态 sendResponse 被栈拒绝时,按消息查找服务端事务并回退事务发送)
     */
    @Setter
    private javax.sip.SipStack sipStack;

    /**
     * 转发 SIP 消息到 WebSocket 客户端
     * <p>
     * 统一入口方法，将 SIP 消息文本通过 WsSessionManager 发送到指定会话。
     *
     * @param sessionId WebSocket 会话 ID
     * @param message   SIP 消息（Request 或 Response）
     * @throws Exception WebSocket 发送异常
     */
    public void toWebSocket(String sessionId, Message message) throws Exception {
        log.debug("[toWebSocket][发送消息到WebSocket客户端] sessionId={}", sessionId);
        wsSessionManager.send(sessionId, message.toString());
    }

    /**
     * 转发消息到FreeSWITCH，支持故障转移
     * 当主节点失败时，自动循环尝试其他可用节点，直到成功或没有可用节点为止
     *
     * @param message SIP消息
     * @param node    首选FreeSWITCH节点
     * @throws Exception 当所有节点都失败时抛出异常
     */
    public void forwardToFreeSwitch(Message message, FsNodeInfo node) throws Exception {
        String callId = SipAnalysisUtil.getCallId(message);
        if (callId == null) {
            log.error("[forwardToFreeSwitch][无法从消息中提取Call-ID]");
            throw new SipProxyException(SipProxyErrorCodeConstants.INTERNAL_SERVER_ERROR, "无法从消息中提取Call-ID");
        }

        log.info("[forwardToFreeSwitch][开始转发消息到FreeSWITCH] fs={}:{}, callId={}",
                node.getSipIp(), node.getSipPort(), callId);

        // 缓存 in-dialog 请求(INFO/BYE/UPDATE 等非 INVITE)的原始顶层 Via（按 CSeq 序号索引）：
        // 响应回送第三方时必须还原为对应请求自身的 Via(各请求 branch 独立)，否则网关 sofia
        // 按 branch 无法关联事务，丢弃 200 OK 后持续重传(重传风暴阻塞后续 BYE 发送，
        // 坐席侧挂断联动延迟可达数十秒)。仅 Request 且非 INVITE 需要缓存；
        // 经 SipSessionManager.updateInboundDialogTopVia 串行化读-改-写，防并发丢失更新。
        if (message instanceof Request req && !Request.INVITE.equalsIgnoreCase(req.getMethod())) {
            Long cseq = SipAnalysisUtil.getCSeqNumber(req);
            String topVia = SipAnalysisUtil.getTopViaBody(req);
            if (cseq != null && topVia != null) {
                sessionManager.updateInboundDialogTopVia(callId, cseq, topVia);
                log.info("[forwardToFreeSwitch][缓存in-dialog请求原始顶层Via] callId={}, method={}, cseq={}, via={}",
                        callId, req.getMethod(), cseq, topVia);
            }
        }

        // 出局方向(第三方网关→FS)的响应必须按 RFC3581 发往原始 FS 出局 INVITE
        // 顶层 Via 的 received:rport(即发起 originate 的 CC FS 实例实际发送端口,如 15580/16580)。
        // 若走 modifyHeadersForForwarding 的 Response 分支会删除整个 Via 栈重建单条 Via
        // (无 received/rport,仅含空值 setRPort()),JAIN-SIP sendResponse 按 Via 路由导致
        // 投递去向异常,CC FS 收不到任何响应(200 OK/500 均丢),持续重传 INVITE 直至 Timer B 超时 408。
        // 因此参照入局响应的还原思路: 用会话缓存的 FS 出局原始顶层 Via 重建 Via 后直接发送,
        // 不走 modifyHeadersForForwarding(不影响入局响应路径与 407/ACK 请求路径)
        if (message instanceof Response response) {
            SessionInfo responseSession = sessionManager.getSessionInfo(callId);
            String fsTopVia = responseSession != null ? responseSession.getOutboundFsTopVia() : null;
            if (fsTopVia != null && !fsTopVia.isEmpty()) {
                try {
                    ViaHeader restoredVia = (ViaHeader) headerFactory.createHeader(ViaHeader.NAME, fsTopVia);

                    // FS→代理的 INVITE 经隧道进来时顶层 Via 被服务端打上
                    // received=127.0.0.1(本机回环地址)。若保留 received:rport,JAIN-SIP 按 RFC3581
                    // 把响应投递到 127.0.0.1:rport(loopback),远端 FS 永远收不到 → Timer B 408。
                    // 因此剔除 received/rport 参数,让 JAIN-SIP 按 Via sent-by(即 FS 公网地址:端口,
                    // 与会话目标 FS 节点一致)投递。仅对 UDP 生效: TCP 方向当前已验证通过
                    // (JAIN-SIP 复用入局持久连接回送),保持原 Via 不引入回归
                    String received = restoredVia.getReceived();
                    boolean receivedIsLocal = received != null
                            && ("127.0.0.1".equals(received) || "::1".equals(received)
                                || "localhost".equalsIgnoreCase(received) || received.equals(localIpAddress));
                    if (receivedIsLocal && SipProxyConstants.TRANSPORT_UDP.equalsIgnoreCase(restoredVia.getTransport())) {
                        restoredVia.removeParameter("received");
                        restoredVia.removeParameter("rport");
                        log.info("[forwardToFreeSwitch][received为本机回环地址,剔除received/rport改按Via sent-by投递] callId={}, 原始via={}, 处理后via={}, 投递目标={}:{}",
                                callId, fsTopVia, restoredVia, restoredVia.getHost(), restoredVia.getPort());
                    }

                    response.removeHeader(ViaHeader.NAME);
                    response.addHeader(restoredVia);

                    // 407 鉴权重发时 CSeq+1(RFC3261 §22.2 新事务必递增),
                    // 网关对重发请求的 200 OK 携带递增后的 CSeq(如 118629022),
                    // 而 CC FS 自身 INVITE 的 CSeq 是原始值(如 118629021),若不还原 sofia
                    // 无法关联事务,丢弃 200 OK → 无 ACK → Timer B 超时 408。
                    // 还原仅影响回送 FS 方向,不改变已发往网关方向的事务状态;
                    // 仅 OUTBOUND/INTERNAL(FS 来源)场景有缓存值,INBOUND 场景自动跳过
                    javax.sip.header.CSeqHeader respCSeq = (javax.sip.header.CSeqHeader) response.getHeader(javax.sip.header.CSeqHeader.NAME);
                    Long fsCSeq = responseSession.getOutboundFsCSeq();
                    if (respCSeq != null && fsCSeq != null && respCSeq.getSeqNumber() != fsCSeq) {
                        log.info("[forwardToFreeSwitch][还原FS出局原始CSeq] callId={}, statusCode={}, 网关侧CSeq={}, 还原为={}",
                                callId, response.getStatusCode(), respCSeq.getSeqNumber(), fsCSeq);
                        respCSeq.setSeqNumber(fsCSeq);
                    }

                    // ws 坐席腿响应的 Contact 是 JsSIP 真实地址(如 sip:1001@公网IP:随机端口;transport=ws),
                    // 直发路径绕过了 modifyHeadersForForwarding 导致未改写,FS 无法向 ws transport 路由 ACK。
                    // 因此条件改写为代理监听地址(仅限回送 FS 方向),普通第三方/FS 响应 Contact 保持原样
                    rewriteWsAgentContactForFs(response, restoredVia, callId);

                    // TCP 腿响应必须沿请求到达的同一连接回送(RFC3261 §18.2.2)。
                    // 实测环境: FS→代理的 TCP INVITE 经 nps 隧道进入,且 cleanViaHeaderForTcpRequest
                    // 已剥离 Via 的 received/rport, sipProviderTcp.sendResponse 按 Via sent-by 只能
                    // 新建公网连接(隧道拓扑下不可达 FS,响应重传直至 FS 腿 408)。
                    // 因此优先用 INVITE 到达时缓存的入站 MessageChannel 直接回送(隧道出口→隧道→FS 原路返回),
                    // 复用失败/未缓存时回退下方 Via 路由直发逻辑。仅对 TCP 生效,UDP 保持现状不引入回归
                    if (SipProxyConstants.TRANSPORT_TCP.equalsIgnoreCase(restoredVia.getTransport())) {
                        gov.nist.javax.sip.stack.MessageChannel inboundChannel = inboundChannelRegistry.get(callId);
                        if (inboundChannel != null) {
                            try {
                                log.info("[forwardToFreeSwitch][复用INVITE入站连接回送响应(RFC3261 §18.2.2)] callId={}, statusCode={}, 复用连接=true, channelTransport={}, channelPeer={}:{}",
                                        callId, response.getStatusCode(), inboundChannel.getTransport(),
                                        inboundChannel.getPeerAddress(), inboundChannel.getPeerPort());
                                // jain-sip-ri 1.2.1.4 的 MessageChannel 无 sendResponse 方法,
                                // 用 sendMessage(SIPMessage) 沿同一连接直发(RI 解析的响应实例即 SIPResponse)
                                inboundChannel.sendMessage((gov.nist.javax.sip.message.SIPMessage) response);
                                log.info("[forwardToFreeSwitch][响应经入站连接回送成功] callId={}, statusCode={}",
                                        callId, response.getStatusCode());
                                return;
                            } catch (Exception e) {
                                log.warn("[forwardToFreeSwitch][复用入站连接回送失败,回退Via路由直发] callId={}, statusCode={}",
                                        callId, response.getStatusCode(), e);
                            }
                        } else {
                            log.info("[forwardToFreeSwitch][未缓存INVITE入站连接,回退Via路由直发] callId={}, statusCode={}, 复用连接=false",
                                    callId, response.getStatusCode());
                        }
                    }

                    log.info("[forwardToFreeSwitch][响应按FS出局原始Via回送(RFC3581)] callId={}, statusCode={}, 最终via={}, 投递目标fs={}:{}",
                            callId, response.getStatusCode(), restoredVia, node.getSipIp(), node.getSipPort());
                    // 注意: 响应沿还原的 FS 原始 Via(实际腿协议)路由, 而发送通道协议由
                    // doForwardToFreeSwitch 按节点 transportProtocol 选择; 节点配置与 FS 实际
                    // 监听协议不一致时该响应可能发送失败(重传), 属防御性边界, 配置时应与实际一致
                    doForwardToFreeSwitch(response, node);
                    return;
                } catch (Exception e) {
                    // 还原失败时回退默认转发逻辑(重建 Via),不阻断响应回送
                    log.warn("[forwardToFreeSwitch][还原FS出局原始Via失败,回退默认转发逻辑] callId={}, via={}",
                            callId, fsTopVia, e);
                }
            }
        }

        List<FsNodeInfo> triedNodes = new ArrayList<>();
        FsNodeInfo currentNode = node;
        while (true) {
            triedNodes.add(currentNode);
            log.info("[forwardToFreeSwitch][第{}次尝试] fs={}:{}",
                    triedNodes.size(), currentNode.getSipIp(), currentNode.getSipPort());

            try {
                // FS 出站腿协议优先按节点配置(transport_protocol), 未配置回退会话入站腿协议;
                // 作为 transportOverride 传入, 保证 Via/Contact transport 与发送通道一致
                String fsTransport = resolveFsTransport(sessionManager.getSessionInfo(callId), currentNode);
                Message modifiedMessage = modifyHeadersForForwarding(message, currentNode.getSipIp(), currentNode.getSipPort(), triedNodes.size(), fsTransport);
                // 委托 SdpProcessor 扩展点处理 SDP（默认透传，父程序可覆盖做 ICE 候选替换等）
                modifiedMessage = sdpProcessor.process(modifiedMessage);
                doForwardToFreeSwitch(modifiedMessage, currentNode);
                if (triedNodes.size() > 1) {
                    log.info("[forwardToFreeSwitch][故障转移成功] 经过{}次尝试，最终节点={}:{}",
                            triedNodes.size(), currentNode.getSipIp(), currentNode.getSipPort());
                } else {
                    log.info("[forwardToFreeSwitch][转发成功] fs={}:{}",
                            currentNode.getSipIp(), currentNode.getSipPort());
                }
                return;

            } catch (Exception e) {
                log.warn("[forwardToFreeSwitch][节点失败] fs={}:{}, 尝试选择备用节点",
                        currentNode.getSipIp(), currentNode.getSipPort());

                FsNodeInfo alternativeNode = nodeManager.selectAlternativeFreeSwitchNode(triedNodes, callId);

                if (alternativeNode == null) {
                    log.error("[forwardToFreeSwitch][所有节点均已尝试失败] 共尝试{}个节点", triedNodes.size());
                    throw new SipProxyException(SipProxyErrorCodeConstants.FORWARD_FAILED,
                            "转发消息到FreeSWITCH失败，已尝试" + triedNodes.size() + "个节点，所有节点均失败");
                }

                currentNode = alternativeNode;
            }
        }
    }

    /**
     * 回送 FS 方向的响应 Contact 条件改写
     * <p>
     * ws 坐席腿(JsSIP)响应的 Contact 是坐席浏览器真实地址(如 sip:1001@公网IP:随机端口;transport=ws),
     * FS 无法向 ws transport 路由 ACK/BYE(代理全程零 ACK → 200 重传 → 前端 SIP 栈拆线 487)。
     * 当 Contact transport 为 ws/wss(或地址与会话记录的 WebSocket 坐席地址一致)时,
     * 改写为代理监听地址 sip:user@代理IP:代理SIP端口;transport=FS侧协议(取还原 Via 的 transport,
     * 保证 FS 按其原出局腿协议路由后续请求);改写逻辑参照 modifyWsProxyHeaders 的 Contact 构造方式。
     * 普通第三方/FS 响应的 Contact 保持原样,异常仅记日志不阻断响应回送。
     *
     * @param response 回送 FS 的响应
     * @param fsVia    还原后的 FS 出局顶层 Via(用于确定改写后 Contact 的 transport)
     * @param callId   会话 Call-ID
     */
    private void rewriteWsAgentContactForFs(Response response, ViaHeader fsVia, String callId) {
        try {
            ContactHeader contactHeader = (ContactHeader) response.getHeader(ContactHeader.NAME);
            if (contactHeader == null) {
                return;
            }
            URI contactUri = contactHeader.getAddress().getURI();
            if (!(contactUri instanceof SipURI contactSipUri)) {
                return;
            }
            // 条件判定: Contact transport 为 ws/wss,或地址与会话缓存的 WebSocket 坐席地址一致
            String contactTransport = contactSipUri.getTransportParam();
            boolean isWsContact = "ws".equalsIgnoreCase(contactTransport) || "wss".equalsIgnoreCase(contactTransport);
            if (!isWsContact) {
                SessionInfo session = sessionManager.getSessionInfo(callId);
                if (session != null && session.getWebsocketContactIp() != null
                        && session.getWebsocketContactIp().equals(contactSipUri.getHost())) {
                    isWsContact = true;
                }
            }
            if (!isWsContact) {
                return;
            }
            // 改写后 Contact 的 transport 取 FS 出局腿协议(还原 Via 的 transport),缺省 UDP;规范化小写
            String fsTransport = (fsVia != null && fsVia.getTransport() != null)
                    ? fsVia.getTransport().toLowerCase() : SipProxyConstants.TRANSPORT_UDP;
            String before = contactHeader.toString();
            SipURI newContactUri = addressFactory.createSipURI(contactSipUri.getUser(), localIpAddress);
            newContactUri.setPort(sipPort);
            newContactUri.setParameter(SipProxyConstants.TRANSPORT_PARAM, fsTransport);
            ContactHeader newContactHeader = headerFactory.createContactHeader(
                    addressFactory.createAddress(newContactUri));
            response.removeHeader(ContactHeader.NAME);
            response.addHeader(newContactHeader);
            log.info("[forwardToFreeSwitch][ws坐席响应Contact改写为代理可达地址] callId={}, 改写前={}, 改写后={}",
                    callId, before, newContactHeader);
        } catch (Exception e) {
            // 改写失败保留原始 Contact,不阻断响应回送 FS
            log.warn("[forwardToFreeSwitch][ws坐席响应Contact改写失败,保留原始Contact] callId={}", callId, e);
        }
    }

    /**
     * 入局呼叫(第三方主叫直连代理)INVITE 事务响应的 Contact 头改写为代理公网地址
     * <p>
     * 设计意图：sipproxy 作为 B2BUA 必须让入局主叫的后续 in-dialog 请求(BYE/ACK/UPDATE/INFO)
     * 回到代理转发，而非直发 FS。FS 的 200 OK 中 Contact 是 FS 自身地址(如 sip:4001234@公网:15580)，
     * 若原样透传，pjsua 等严格 RFC3261 客户端会把 BYE 发往该地址；该地址在 FS external profile 上
     * 无对应 dialog(呼叫经代理建立)，FS 丢弃 BYE 不回 200 OK → 客户端按重传退避重发多次(约 10s)
     * → 最终靠媒体超时才挂断，坐席侧挂断联动延迟 10s+。
     * <p>
     * 约束：仅处理 INVITE 事务响应(通过 CSeq method 判断)，REGISTER/OPTIONS/SUBSCRIBE 等
     * 响应(Contact 承载注册/订阅地址语义)保持原样；WS 坐席腿响应走 forwardToWebSocketByUser，
     * 不受此方法影响。
     *
     * @param response 待回送入局主叫的响应
     * @param callId   呼叫标识
     */
    private void rewriteInboundInviteContactForClient(Response response, String callId) {
        try {
            // 仅 INVITE 事务响应需要改写(Contact 决定 in-dialog 请求路由)
            CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
            if (cseq == null || !Request.INVITE.equalsIgnoreCase(cseq.getMethod())) {
                return;
            }
            ContactHeader contactHeader = (ContactHeader) response.getHeader(ContactHeader.NAME);
            if (contactHeader == null) {
                return;
            }
            String before = contactHeader.toString();
            SipURI contactUri = addressFactory.createSipURI(null, properties.getSip().getPublicIp());
            contactUri.setPort(properties.getSip().getPublicPort());
            // transport 沿用 FS 出局腿协议(入局主叫侧),缺省 UDP
            String fsTransport = SipProxyConstants.TRANSPORT_UDP;
            ViaHeader topVia = (ViaHeader) response.getHeader(ViaHeader.NAME);
            if (topVia != null && topVia.getTransport() != null) {
                fsTransport = topVia.getTransport().toLowerCase();
            }
            contactUri.setParameter(SipProxyConstants.TRANSPORT_PARAM, fsTransport);
            ContactHeader newContactHeader = headerFactory.createContactHeader(
                    addressFactory.createAddress(contactUri));
            response.removeHeader(ContactHeader.NAME);
            response.addHeader(newContactHeader);
            log.info("[forwardToThirdParty][入局INVITE响应Contact改写为代理地址] callId={}, 改写前={}, 改写后={}",
                    callId, before, newContactHeader);
        } catch (Exception e) {
            // 改写失败保留原始 Contact,不阻断响应回送
            log.warn("[forwardToThirdParty][入局INVITE响应Contact改写失败,保留原始Contact] callId={}", callId, e);
        }
    }

    /**
     * 解析转发到 FS 的出站腿传输协议
     * <p>
     * 优先级：FS 节点配置 transportProtocol（1=UDP，2=TCP，非 2 一律 UDP）> 会话入站腿
     * toSipTransport（INVITE Via transport）> UDP 兜底。
     * 需求背景：FS 腿协议应由 FS 节点实际监听协议决定（如 NPS 内网穿透仅支持 TCP），
     * 而非主叫侧协议；存量节点未配置 transportProtocol 时回退现状（toSipTransport），
     * 保证向后兼容不破坏既有部署。
     *
     * @param sessionInfo 会话信息（可能为 null，提供入站腿协议兜底）
     * @param node        目标 FS 节点（提供配置协议优先项）
     * @return 传输协议字符串（tcp/udp）
     */
    private String resolveFsTransport(SessionInfo sessionInfo, FsNodeInfo node) {
        if (node != null && node.getTransportProtocol() != null) {
            return node.resolveSipTransport();
        }
        if (sessionInfo != null && sessionInfo.getToSipTransport() != null) {
            return sessionInfo.getToSipTransport();
        }
        return SipProxyConstants.TRANSPORT_UDP;
    }

    /**
     * 实际的FreeSWITCH转发逻辑（无故障转移）
     *
     * @param message SIP消息
     * @param node    目标FS节点
     */
    private void doForwardToFreeSwitch(Message message, FsNodeInfo node) throws Exception {
        log.info("[doForwardToFreeSwitch][开始转发消息到FreeSWITCH] fs={}:{}, message={}",
                node.getSipIp(), node.getSipPort(), message.getClass().getSimpleName());

        String callId = SipAnalysisUtil.getCallId(message);
        // FS 出站腿协议: 节点配置优先(transport_protocol), 未配置回退会话入站腿协议, UDP 兜底
        String transport = resolveFsTransport(sessionManager.getSessionInfo(callId), node);

        SipProvider targetProvider = SipProxyConstants.TRANSPORT_TCP.equalsIgnoreCase(transport) ? sipProviderTcp : sipProvider;
        log.info("[doForwardToFreeSwitch][选择传输协议] transport={}, provider={}", transport,
                targetProvider == sipProviderTcp ? "TCP" : "UDP");

        try {
            if (message instanceof Request request) {
                Header gatewayIdHeader = request.getHeader("X-Gateway-Id");
                if (gatewayIdHeader != null) {
                    log.info("[doForwardToFreeSwitch][X-Gateway-Id头域已保留] callId={}, X-Gateway-Id={}",
                            callId, gatewayIdHeader);
                }
                log.info("[doForwardToFreeSwitch][请求发送到FreeSWITCH] method={}, fs={}:{}",
                        request.getMethod(), node.getSipIp(), node.getSipPort());
                targetProvider.sendRequest(request);
                log.info("[doForwardToFreeSwitch][请求已发送到FreeSWITCH]");
            } else if (message instanceof Response response) {
                log.info("[doForwardToFreeSwitch][响应发送到FreeSWITCH] statusCode={}, fs={}:{}",
                        response.getStatusCode(), node.getSipIp(), node.getSipPort());
                try {
                    targetProvider.sendResponse(response);
                } catch (Exception sendError) {
                    // INVITE 注册入站连接时已建服务端事务,无状态 sendResponse
                    // 被栈拒绝时回退按事务发送(详见 trySendResponseViaTransaction)
                    if (!trySendResponseViaTransaction(response, callId)) {
                        throw sendError;
                    }
                }
                log.info("[doForwardToFreeSwitch][响应已发送到FreeSWITCH]");
            }
        } catch (Exception e) {
            log.error("[doForwardToFreeSwitch][转发消息到FreeSWITCH失败] fs={}:{}",
                    node.getSipIp(), node.getSipPort(), e);
            throw new SipProxyException(SipProxyErrorCodeConstants.FORWARD_FAILED,
                    "转发消息到FreeSWITCH失败: " + e.getMessage(), e);
        }
    }

    public void forwardToThirdParty(Message message, GatewayInfo node) throws Exception {
        // node 可能为 null: 原生 SIP 终端直连入局且来源 IP 未匹配网关列表时,
        // 响应回送依赖 inboundTopVia + 入站连接注册表,不依赖网关节点;请求类消息仍必须指定节点
        // transport 语义差异:
        //   - 响应分支/INBOUND 回送: 沿入站腿协议(toSipTransport=INVITE Via transport, UDP 兜底,
        //     RFC3261 §18.2.2 连接导向传输同路径回送), 与网关配置无关
        //   - 请求分支非 INBOUND(出局腿/无会话 fallback): 按网关自身协议(transport_protocol)选择,
        //     与 forwardToOutboundGateway 同源, 见下方请求分支判定
        log.info("[forwardToThirdParty][开始转发消息到第三方SIP服务] tp={}:{}, message={}",
                node != null ? node.getAddress() : "inbound(入站连接)",
                node != null ? node.getPort() : null, message.getClass().getSimpleName());

        String callId = SipAnalysisUtil.getCallId(message);
        SessionInfo sessionInfo = callId != null ? sessionManager.getSessionInfo(callId) : null;
        // 响应回送第三方必须沿入站腿协议(toSipTransport=INVITE Via transport, UDP 兜底,
        // 含 RFC3581 入站 Via 还原回送)——保持现状勿改; 请求分支在下方按场景覆盖
        String transport = SipProxyConstants.TRANSPORT_UDP;
        if (sessionInfo != null && sessionInfo.getToSipTransport() != null) {
            transport = sessionInfo.getToSipTransport();
        }

        SipProvider targetProvider = SipProxyConstants.TRANSPORT_TCP.equalsIgnoreCase(transport) ? sipProviderTcp : sipProvider;
        log.info("[forwardToThirdParty][选择传输协议] transport={}", transport);

        try {
            if (message instanceof Response response) {
                // RFC3581: 响应回送第三方主叫必须发往原始入局 INVITE 顶层 Via 的 received:rport
                // (rport 缺失时 JAIN-SIP 按 Via 规则自然回退 sent-by host:port)。
                // 此前响应复用 modifyHeadersForForwarding 按第三方网关节点静态配置 address:port
                // 重写顶层 Via 发送,软电话等随机端口主叫(NAT 后 rport≠静态端口)收不到 200 OK,
                // 持续重传 INVITE 直至 408。改为还原会话缓存的入局原始顶层 Via 后直接发送,
                // 响应不做 Request-URI/Contact 改写(响应无 Request-URI,Contact 保持原样回送)
                SessionInfo responseSession = callId != null ? sessionManager.getSessionInfo(callId) : null;
                // in-dialog 请求(INFO/BYE/UPDATE 等非 INVITE)的响应须还原为对应请求自身的顶层 Via
                // (各请求 branch 独立)：网关 sofia 按 branch 关联事务，若复用 INVITE 的 inboundTopVia
                // 会因 branch 不匹配丢弃 200 OK → 持续重传风暴阻塞后续请求。INVITE 响应仍走 RFC3581。
                String inboundTopVia = responseSession != null ? responseSession.getInboundTopVia() : null;
                CSeqHeader respCSeqHeader = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
                if (respCSeqHeader != null && !Request.INVITE.equalsIgnoreCase(respCSeqHeader.getMethod())) {
                    String dialogVia = responseSession != null
                            ? responseSession.getInboundDialogTopVia(Long.valueOf(respCSeqHeader.getSeqNumber())) : null;
                    if (dialogVia != null && !dialogVia.isEmpty()) {
                        inboundTopVia = dialogVia;
                        log.info("[forwardToThirdParty][in-dialog响应按请求自身Via还原] callId={}, method={}, cseq={}, via={}",
                                callId, respCSeqHeader.getMethod(), respCSeqHeader.getSeqNumber(), dialogVia);
                    } else {
                        log.warn("[forwardToThirdParty][in-dialog响应未缓存原请求Via,回退INVITE Via] callId={}, method={}, cseq={}",
                                callId, respCSeqHeader.getMethod(), respCSeqHeader.getSeqNumber());
                    }
                }
                if (inboundTopVia != null && !inboundTopVia.isEmpty()) {
                    try {
                        Header inboundVia = headerFactory.createHeader(ViaHeader.NAME, inboundTopVia);
                        response.removeHeader(ViaHeader.NAME);
                        response.addHeader(inboundVia);
                        log.info("[forwardToThirdParty][响应按入局原始Via回送(RFC3581 received:rport)] callId={}, via={}",
                                callId, inboundTopVia);
                    } catch (Exception e) {
                        // 还原失败时保留响应当前顶层 Via(JAIN-SIP 仍按其 received:rport 发送),不阻断回送
                        log.warn("[forwardToThirdParty][还原入局原始Via失败,保留响应当前Via] callId={}, via={}",
                                callId, inboundTopVia, e);
                    }
                } else {
                    log.warn("[forwardToThirdParty][会话未缓存入局Via,保留响应当前Via回送] callId={}, tp={}:{}",
                            callId, node != null ? node.getAddress() : "inbound(入站连接)",
                            node != null ? node.getPort() : null);
                }
                // 入局呼叫(第三方主叫直连代理发起 INVITE)的 INVITE 事务响应:
                // Contact 头改写为代理公网地址(publicIp:publicPort),保证客户端后续
                // in-dialog 请求(BYE/ACK/UPDATE/INFO)按 RFC3261 路由回代理转发。
                // 此前保持原样回送导致 FS 的 200 OK Contact(fs:15580)被透传, pjsua 等
                // 终端按该地址直发 BYE 打错端口 → FS 无此 dialog 不回 200 OK → 客户端
                // 按重传退避重发 6 次(约 10s) → 最终靠媒体超时才挂断, 坐席侧挂断联动
                // 延迟 10s+。仅改 INVITE 事务(REGISTER/OPTIONS 等响应 Contact 不动)。
                if (inboundTopVia != null && !inboundTopVia.isEmpty()) {
                    rewriteInboundInviteContactForClient(response, callId);
                }
                // 委托 SdpProcessor 扩展点处理 SDP（默认透传，父程序可覆盖做 ICE 候选替换等）
                Message processed = sdpProcessor.process(response);
                if (processed instanceof Response processedResponse) {
                    log.info("[forwardToThirdParty][响应已发送到第三方SIP服务] statusCode={}, callId={}",
                            processedResponse.getStatusCode(), callId);
                    try {
                        targetProvider.sendResponse(processedResponse);
                    } catch (Exception sendError) {
                        // TCP 直连第三方(软电话/普通SIP终端)入局 INVITE 已建服务端事务,
                        // 无状态 sendResponse 被栈拒绝(Transaction exists -- cannot send response statelessly),
                        // 且事务兜底会因响应 CSeq(FS 腿)与事务 CSeq(第三方 INVITE)不一致校验失败
                        // (Response does not belong to this transaction),导致 FS 的 100/180/200/4xx
                        // 响应全部丢弃、呼叫卡死。因此优先复用 INVITE 入站 TCP 连接原路回送
                        // (RFC3261 §18.2.2,与 forwardToFreeSwitch 回送方案一致),失败再回退事务兜底
                        if (!trySendResponseViaInboundChannel(processedResponse, callId)
                                && !trySendResponseViaTransaction(processedResponse, callId)) {
                            throw sendError;
                        }
                    }
                }
                return;
            }
            // 请求类消息保持既有转发路径: Request-URI/Contact/顶层 Via 改写为目标节点地址
            if (node == null) {
                // 请求类消息必须指定网关节点(入站连接注册表仅缓存入局 INVITE 的连接,会话内请求
                // 需按目标节点新建连接发送),调用方(forwardRequestByRegistration 等)已保证非空
                throw new IllegalArgumentException("[forwardToThirdParty][请求类消息转发必须指定第三方节点] node=null");
            }
            String targetIp = node.getAddress();
            Integer targetPort = node.getPort() != null ? node.getPort() : 5060;
            // 出局 in-dialog 请求（ACK/BYE/UPDATE 等）目标为出局网关时，发送通道与 Via/Contact
            // transport 按网关自身协议（transport_protocol：1=UDP，2=TCP）决定，与 FS 腿
            // toSipTransport 解耦——与 forwardToOutboundGateway/DefaultOutboundGatewayRewriter/
            // GatewayAuthManager 同源。否则 FS 腿 TCP 时误走 sipProviderTcp 发往 UDP 网关端口，信令丢失。
            // 仅当网关显式配置 transport_protocol 且呼叫为出局方向时才覆盖：未配置（存量网关）
            // 与 INBOUND 场景（FS→第三方主叫回送）保持 toSipTransport（入站腿协议，RFC3261
            // §18.2.2），与 resolveFsTransport 的"未配置回退入站腿"语义一致，避免存量行为漂移
            String callType = sessionInfo != null ? sessionInfo.getCallType() : null;
            boolean isOutboundLeg = SipProxyConstants.CALL_TYPE_OUTBOUND.equals(callType)
                    || SipProxyConstants.CALL_TYPE_INTERNAL.equals(callType);
            if (node != null && node.getTransportProtocol() != null && isOutboundLeg) {
                transport = node.resolveSipTransport();
                targetProvider = SipProxyConstants.TRANSPORT_TCP.equalsIgnoreCase(transport)
                        ? sipProviderTcp : sipProvider;
                log.info("[forwardToThirdParty][请求按网关自身协议选择发送通道] callId={}, method={}, transportProtocol={}, transport={}",
                        callId, message instanceof Request request ? request.getMethod() : message.getClass().getSimpleName(),
                        node.getTransportProtocol(), transport);
            }
            Message modifiedMessage = modifyHeadersForForwarding(message, targetIp, targetPort, 0, transport);
            // 委托 SdpProcessor 扩展点处理 SDP（默认透传，父程序可覆盖做 ICE 候选替换等）
            modifiedMessage = sdpProcessor.process(modifiedMessage);
            if (modifiedMessage instanceof Request request) {
                targetProvider.sendRequest(request);
                log.info("[forwardToThirdParty][请求已发送到第三方SIP服务] method={}, tp={}:{}",
                        request.getMethod(), targetIp, targetPort);
            }
        } catch (Exception e) {
            log.error("[forwardToThirdParty][转发消息到第三方SIP服务失败] tp={}:{}",
                    node != null ? node.getAddress() : "inbound(入站连接)",
                    node != null ? node.getPort() : null, e);
            throw new SipProxyException(SipProxyErrorCodeConstants.FORWARD_FAILED,
                    "转发消息到第三方SIP服务失败: " + e.getMessage(), e);
        }
    }

    public void forwardToWebSocketByUser(String username, String domain, Message message) throws Exception {
        log.info("[forwardToWebSocketByUser][转发到WebSocket客户端] username={}, domain={}", username, domain);

        String sessionId = sessionManager.getSessionIdByUser(username, domain);
        if (sessionId == null) {
            log.error("[forwardToWebSocketByUser][未找到WebSocket会话] username={}, domain={}", username, domain);
            throw new SipProxyException(SipProxyErrorCodeConstants.SESSION_NOT_FOUND,
                    "未找到WebSocket会话: " + username + "@" + domain);
        }

        Message modifiedMessage = modifyWsProxyHeaders(message);
        // 委托 SdpProcessor 扩展点处理 SDP（默认透传，父程序可覆盖做 ICE 候选替换等）
        modifiedMessage = sdpProcessor.process(modifiedMessage);
        toWebSocket(sessionId, modifiedMessage);
        log.info("[forwardToWebSocketByUser][请求已转发到WebSocket客户端] sessionId={}, username={}", sessionId, username);
    }

    /**
     * 沿 INVITE 入站 TCP 连接原路回送响应(RFC3261 §18.2.2)
     * <p>
     * 背景: TCP 直连第三方(软电话/普通 SIP 终端)的 INVITE 在
     * {@code SipProxyService.cacheInboundChannelForInvite} 中已按 callId 注册入站连接。
     * 此类请求已建服务端事务,无状态 sendResponse 被栈拒绝,且事务兜底因响应 CSeq
     * (FS 腿)与事务 CSeq(第三方 INVITE)不一致而校验失败。直接复用入站
     * MessageChannel 发送是连接导向传输的规范做法,同时规避按 Via sent-by 新建连接
     * 不可达(NAT/随机端口)的风险。
     *
     * @param response 待回送响应
     * @param callId   Call-ID(仅用于日志与入站连接注册表查询)
     * @return true=沿入站连接发送成功; false=未注册连接或发送失败,由调用方继续回退
     */
    private boolean trySendResponseViaInboundChannel(Response response, String callId) {
        try {
            gov.nist.javax.sip.stack.MessageChannel inboundChannel = inboundChannelRegistry.get(callId);
            if (inboundChannel == null) {
                log.warn("[trySendResponseViaInboundChannel][未注册入站连接,无法沿原路回送] callId={}, statusCode={}",
                        callId, response.getStatusCode());
                return false;
            }
            // jain-sip-ri 1.2.1.4 的 MessageChannel 无 sendResponse 方法,
            // 用 sendMessage(SIPMessage) 沿同一连接直发(与 forwardToFreeSwitch 回送方式一致)
            inboundChannel.sendMessage((gov.nist.javax.sip.message.SIPMessage) response);
            log.info("[trySendResponseViaInboundChannel][沿INVITE入站连接回送响应成功] callId={}, statusCode={}, peer={}:{}",
                    callId, response.getStatusCode(), inboundChannel.getPeerAddress(), inboundChannel.getPeerPort());
            return true;
        } catch (Exception e) {
            log.warn("[trySendResponseViaInboundChannel][沿入站连接回送失败,继续回退] callId={}, statusCode={}",
                    callId, response.getStatusCode(), e);
            return false;
        }
    }

    /**
     * 无状态 sendResponse 被栈拒绝时的事务兜底发送
     * <p>
     * 背景: 入站连接注册(路径③)主动为 TCP INVITE 创建了服务端事务,此后 JAIN-SIP
     * 栈拒绝同一事务的无状态回送,抛 "Transaction exists -- cannot send response statelessly"。
     * 此时按响应的顶层 Via branch+CSeq 从栈内查到该服务端事务并经其发送——事务的
     * 发送通道即请求到达的入站连接,恰好满足 RFC3261 §18.2.2 同连接回送要求。
     *
     * @param response 待回送响应
     * @param callId   Call-ID(仅用于日志)
     * @return true=事务兜底发送成功; false=未找到事务或发送失败,由调用方继续原异常路径
     */
    private boolean trySendResponseViaTransaction(Response response, String callId) {
        try {
            if (!(sipStack instanceof gov.nist.javax.sip.stack.SIPTransactionStack txStack)) {
                return false;
            }
            gov.nist.javax.sip.stack.SIPTransaction transaction =
                    txStack.findTransaction((gov.nist.javax.sip.message.SIPMessage) response, true);
            if (!(transaction instanceof javax.sip.ServerTransaction serverTransaction)) {
                log.warn("[trySendResponseViaTransaction][栈内未找到对应服务端事务,无法兜底发送] callId={}, statusCode={}",
                        callId, response.getStatusCode());
                return false;
            }
            serverTransaction.sendResponse(response);
            log.info("[trySendResponseViaTransaction][无状态回送被栈拒绝,已改经服务端事务发送成功] callId={}, statusCode={}",
                    callId, response.getStatusCode());
            return true;
        } catch (Exception e) {
            log.warn("[trySendResponseViaTransaction][事务兜底发送失败] callId={}, statusCode={}",
                    callId, response.getStatusCode(), e);
            return false;
        }
    }

    /**
     * 修改SIP消息头以便正确转发到FreeSWITCH或第三方SIP服务
     * <p>内部无调用方(所有转发路径均显式传 transportOverride), 保留仅为兼容外部集成方;
     * 传 null 时 Via/Contact 的 transport 取会话入站腿协议(sessionInfo.getToSipTransport())
     */
    public Message modifyHeadersForForwarding(Message message, String targetIp, Integer targetPort, int attemptCount)
            throws Exception {
        return modifyHeadersForForwarding(message, targetIp, targetPort, attemptCount, null);
    }

    /**
     * 修改SIP消息头以便正确转发到FreeSWITCH或第三方SIP服务
     *
     * @param transportOverride 传输协议覆盖项: 非空时 Via/Contact 的 transport 使用该值,
     *                          不再取 sessionInfo.getToSipTransport()。
     *                          适用场景: 出局网关腿的 transport 必须按网关自身协议
     *                          (forwardToOutboundGateway 出局 INVITE 与 forwardToThirdParty
     *                          出局 in-dialog 请求 ACK/BYE 均传覆盖值),与 FS 腿 transport 解耦
     */
    public Message modifyHeadersForForwarding(Message message, String targetIp, Integer targetPort, int attemptCount,
                                              String transportOverride)
            throws Exception {
        log.debug("[modifyHeadersForForwarding][开始修改SIP头] message={}, target={}:{}, attemptCount={}",
                message.getClass().getSimpleName(), targetIp, targetPort, attemptCount);
        String callId = SipAnalysisUtil.getCallId(message);
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo == null) {
            log.warn("[modifyHeadersForForwarding][未找到会话信息，跳过sip信息处理] callId={}", callId);
            return message;
        }
        String branchId = SipAnalysisUtil.getBranch(message);
        try {
            String transport = "udp";
            if (transportOverride != null && !transportOverride.isEmpty()) {
                transport = transportOverride;
            } else if (sessionInfo.getToSipTransport() != null) {
                transport = sessionInfo.getToSipTransport();
            }

            String sipProxyPublicIp = properties.getSip().getPublicIp();
            int sipProxyPublicPort = properties.getSip().getPublicPort();

            try {
                ContactHeader contactHeader = (ContactHeader) message.getHeader(ContactHeader.NAME);
                if (contactHeader != null) {
                    String contactHost = sipProxyPublicIp;
                    int contactPort = sipProxyPublicPort;
                    SipURI contactUri = addressFactory.createSipURI(null, contactHost);
                    contactUri.setPort(contactPort);
                    contactUri.setParameter(SipProxyConstants.TRANSPORT_PARAM, transport);
                    Address contactAddress = addressFactory.createAddress(contactUri);
                    ContactHeader newContactHeader = headerFactory.createContactHeader(contactAddress);

                    message.removeHeader(ContactHeader.NAME);
                    message.addHeader(newContactHeader);
                    log.info("[modifyHeadersForForwarding][已替换Contact头] callId={}, newContact={}", callId,
                            newContactHeader);
                }
            } catch (Exception e) {
                log.error("[modifyHeadersForForwarding][处理Contact头失败] callId={}", callId, e);
            }

            if (message instanceof Request request) {
                try {
                    ViaHeader viaHeader = headerFactory.createViaHeader(
                            sipProxyPublicIp,
                            sipProxyPublicPort,
                            transport,
                            null);
                    viaHeader.setBranch(branchId);
                    viaHeader.setRPort();
                    message.removeHeader(ViaHeader.NAME);
                    message.addHeader(viaHeader);
                    log.debug("[modifyHeadersForForwarding][已添加Via头] via={}:{}, transport={}", sipProxyPublicIp,
                            sipProxyPublicPort, transport);
                } catch (Exception e) {
                    log.error("[modifyHeadersForForwarding][处理Via头失败] callId={}", callId, e);
                }

                String originalUser = null;
                try {
                    URI originalUri = request.getRequestURI();
                    if (originalUri instanceof SipURI originalSipUri) {
                        originalUser = originalSipUri.getUser();
                    }
                } catch (Exception e) {
                    log.warn("[modifyHeadersForForwarding][提取原始Request-URI user失败] callId={}", callId, e);
                }
                SipURI requestUri = addressFactory.createSipURI(originalUser, targetIp);
                requestUri.setPort(targetPort);
                request.setRequestURI(requestUri);
                log.debug("[modifyHeadersForForwarding][已修改Request-URI] user={}, uri={}:{}",
                        originalUser, targetIp, targetPort);
            } else if (message instanceof Response response) {
                try {
                    ViaHeader viaHeader = headerFactory.createViaHeader(
                            targetIp,
                            targetPort,
                            transport,
                            null);
                    viaHeader.setBranch(branchId);
                    viaHeader.setRPort();
                    response.removeHeader(ViaHeader.NAME);
                    response.addHeader(viaHeader);
                    log.debug("[modifyHeadersForForwarding][response已添加Via头] via={}:{}, transport={}", targetIp,
                            targetPort, transport);

                } catch (Exception e) {
                    log.error("[modifyHeadersForForwarding][response处理Via头失败] callId={}", callId, e);
                }
            }

            validateIceCandidateInSdp(message);
            return message;
        } catch (Exception e) {
            log.error("[modifyHeadersForForwarding][修改SIP头失败]", e);
            throw e;
        }
    }

    /**
     * 校验SDP中ICE候选的完整性
     */
    private void validateIceCandidateInSdp(Message message) {
        if (!(message instanceof Request)) {
            return;
        }
        ContentTypeHeader contentTypeHeader = (ContentTypeHeader) message.getHeader(ContentTypeHeader.NAME);
        if (contentTypeHeader == null) {
            return;
        }
        if (!"application".equals(contentTypeHeader.getContentType())
                || !"sdp".equals(contentTypeHeader.getContentSubType())) {
            return;
        }
        Object content = message.getContent();
        if (content == null) {
            return;
        }
        String sdpBody = content.toString();
        if (sdpBody.isEmpty()) {
            return;
        }
        boolean hasIceUfrag = sdpBody.contains("a=ice-ufrag");
        boolean hasCandidate = sdpBody.contains("a=candidate");
        if (hasIceUfrag && !hasCandidate) {
            String callId = SipAnalysisUtil.getCallId(message);
            log.warn("[validateIceCandidateInSdp][ICE 候选缺失，可能影响媒体协商] callId={}", callId);
        }
    }

    public Message modifyWsProxyHeaders(Message message) {
        log.debug("[modifyWsProxyHeaders][开始修改WebSocket代理SIP头] message={}", message);

        String callId = SipAnalysisUtil.getCallId(message);
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo == null) {
            log.warn("[modifyWsProxyHeaders][未找到会话信息，跳过sip信息处理] callId={}", callId);
            return message;
        }
        String branchId = SipAnalysisUtil.getBranch(message);
        String transport = "ws";

        try {
            ContactHeader contactHeader = (ContactHeader) message.getHeader(ContactHeader.NAME);
            if (contactHeader != null) {
                SipURI contactUri = addressFactory.createSipURI(null, localIpAddress);
                contactUri.setPort(sipPort);
                contactUri.setParameter(SipProxyConstants.TRANSPORT_PARAM, transport);
                Address contactAddress = addressFactory.createAddress(contactUri);
                ContactHeader newContactHeader = headerFactory.createContactHeader(contactAddress);

                message.removeHeader(ContactHeader.NAME);
                message.addHeader(newContactHeader);
                log.info("[modifyWsProxyHeaders][已替换Contact头] callId={}, newContact={}", callId, newContactHeader);
            }
        } catch (Exception e) {
            log.error("[modifyWsProxyHeaders][处理Contact头失败] callId={}", callId, e);
        }

        if (message instanceof Request request) {
            try {
                ViaHeader viaHeader = headerFactory.createViaHeader(
                        localIpAddress,
                        sipPort,
                        transport,
                        null);
                viaHeader.setBranch(branchId);
                viaHeader.setRPort();
                message.removeHeader(ViaHeader.NAME);
                message.addHeader(viaHeader);
                log.debug("[modifyWsProxyHeaders][已添加Via头] via={}:{}, transport={}", localIpAddress, sipPort, transport);
            } catch (Exception e) {
                log.error("[modifyWsProxyHeaders][处理Via头失败] callId={}", callId, e);
            }
            try {
                SipURI requestUri;
                if (sessionInfo.getWebsocketContactIp() != null) {
                    requestUri = addressFactory.createSipURI(sessionInfo.getWebsocketContactName(),
                            sessionInfo.getWebsocketContactIp());
                    requestUri.setPort(sessionInfo.getWebsocketContactPort());
                    requestUri.setParameter(SipProxyConstants.TRANSPORT_PARAM,
                            sessionInfo.getWebsocketContactTransport());
                } else {
                    URI originalRequestUri = request.getRequestURI();
                    String userName = null;
                    if (originalRequestUri instanceof SipURI originalSipUri) {
                        userName = originalSipUri.getUser();
                        // FS originate 目标使用 "坐席号&域名" 格式(如 "1002&1.com%3A1"),
                        // 转发到 WebSocket 时 JsSIP 期望 Request-URI user 部分为纯坐席号(如 "1002"),
                        // 否则无法匹配 JsSIP 注册 URI,不触发 newRTCSession 事件,坐席B 收不到来电.
                        // 此处去掉 '&' 及之后的域名部分,仅保留坐席号.
                        if (userName != null) {
                            int ampIdx = userName.indexOf('&');
                            if (ampIdx >= 0) {
                                userName = userName.substring(0, ampIdx);
                            }
                        }
                    }
                    requestUri = addressFactory.createSipURI(userName, localIpAddress);
                    requestUri.setPort(sipPort);
                    requestUri.setParameter(SipProxyConstants.TRANSPORT_PARAM, transport);
                }
                request.setRequestURI(requestUri);
                log.info("[modifyWsProxyHeaders][已修改Request-URI为WebSocket地址] callId={}, newRequestUri={}", callId,
                        requestUri);
            } catch (Exception e) {
                log.error("[modifyWsProxyHeaders][修改Request-URI失败] callId={}", callId, e);
            }
        }

        modifySdpForWebSocket(message, sessionInfo, callId);

        return message;
    }

    /**
     * 修改转发到WebSocket的SIP消息中的SDP媒体地址
     */
    private void modifySdpForWebSocket(Message message, SessionInfo sessionInfo, String callId) {
        ContentTypeHeader contentTypeHeader = (ContentTypeHeader) message.getHeader(ContentTypeHeader.NAME);
        if (contentTypeHeader == null) {
            log.debug("[modifySdpForWebSocket][无Content-Type头,跳过] callId={}", callId);
            return;
        }
        if (!"application".equals(contentTypeHeader.getContentType())
                || !"sdp".equals(contentTypeHeader.getContentSubType())) {
            log.debug("[modifySdpForWebSocket][Content-Type非application/sdp,跳过] callId={}, type={}/{}",
                    callId, contentTypeHeader.getContentType(), contentTypeHeader.getContentSubType());
            return;
        }
        Object content = message.getContent();
        if (content == null) {
            log.debug("[modifySdpForWebSocket][SDP内容为null,跳过] callId={}", callId);
            return;
        }
        String sdpBody;
        if (content instanceof byte[] bytes) {
            sdpBody = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } else {
            sdpBody = content.toString();
        }
        if (sdpBody.isEmpty()) {
            log.debug("[modifySdpForWebSocket][SDP内容为空,跳过] callId={}", callId);
            return;
        }
        FsNodeInfo fsNode = sessionInfo.getFreeSwitchNode();
        if (fsNode == null || fsNode.getSipIp() == null || fsNode.getSipIp().isEmpty()) {
            log.warn("[modifySdpForWebSocket][SessionInfo中freeSwitchNode为null或ip为空,无法替换SDP] callId={}, fsNode={}",
                    callId, fsNode == null ? "null" : fsNode.getSipIp());
            return;
        }
        String fsPublicIp = fsNode.getSipIp();
        Pattern pattern = Pattern.compile("c=IN IP4 ([0-9.]+)");
        Matcher matcher = pattern.matcher(sdpBody);
        if (!matcher.find()) {
            log.debug("[modifySdpForWebSocket][SDP中未找到c=IN IP4行,跳过] callId={}", callId);
            return;
        }
        String currentIp = matcher.group(1);
        if (currentIp.equals(fsPublicIp)) {
            log.debug("[modifySdpForWebSocket][SDP中IP与FS公网IP一致,无需替换] callId={}, ip={}", callId, currentIp);
            return;
        }
        log.info("[modifySdpForWebSocket][替换SDP中的FS媒体地址] callId={}, oldIp={}, newIp={}",
                callId, currentIp, fsPublicIp);
        // 精确替换 c=IN IP4 行和 a=candidate 行中的 FS 内网 IP 为公网 IP
        // 需求背景: FS 部署在内网(10.2.0.14), 坐席通过公网(62.234.191.165)连接 SIP 代理。
        //   FS 生成的 SDP 中 c= 行和 a=candidate 行均包含 FS 内网 IP, 坐席无法直接访问,
        //   必须替换为公网 IP 才能建立 ICE 连接和 DTLS-SRTP 握手。
        // 设计约束:
        //   1. c=IN IP4 行: 连接信息行, 直接替换 IP
        //   2. a=candidate 行: ICE 候选地址行, 只替换第 5 个字段(连接地址)中的 IP
        //   3. 不使用全局 sdpBody.replace(): 避免误改 o= 行或其他位置的 IP
        String escapedCurrentIp = java.util.regex.Matcher.quoteReplacement(currentIp);
        String escapedFsPublicIp = java.util.regex.Matcher.quoteReplacement(fsPublicIp);
        // 第一步: 替换 c=IN IP4 行
        String newSdpBody = sdpBody.replaceAll(
                "c=IN IP4 " + escapedCurrentIp,
                "c=IN IP4 " + escapedFsPublicIp);
        // 第二步: 替换 a=candidate 行中的连接地址(candidate 格式: a=candidate:<id> <component> <proto> <priority> <ip> <port> ...)
        newSdpBody = newSdpBody.replaceAll(
                "(a=candidate:\\S+ \\S+ \\S+ \\S+ )" + escapedCurrentIp,
                "$1" + escapedFsPublicIp);
        try {
            message.setContent(newSdpBody, contentTypeHeader);
            log.info("[modifySdpForWebSocket][SDP替换完成] callId={}", callId);
        } catch (Exception e) {
            log.error("[modifySdpForWebSocket][更新SDP内容失败] callId={}", callId, e);
        }
    }

    /**
     * 转发INVITE到出局网关
     *
     * @param request   SIP INVITE请求
     * @param gatewayId 网关ID
     */
    public void forwardToOutboundGateway(Request request, String gatewayId) throws Exception {
        log.info("[forwardToOutboundGateway][开始转发到出局网关] gatewayId={}", gatewayId);

        GatewayInfo gateway = gatewayProvider.getGatewayById(gatewayId);
        if (gateway == null) {
            log.error("[forwardToOutboundGateway][网关不存在] gatewayId={}", gatewayId);
            throw new SipProxyException(SipProxyErrorCodeConstants.GATEWAY_NOT_FOUND,
                    "网关不存在: " + gatewayId);
        }
        // 防御性拷贝：后续会用注册绑定地址回填 address/port/transportProtocol 并传扩展点
        // rewriteForOutbound，直接改写 provider 返回实例会污染“按 ID 缓存实例”的自研实现
        // （共享状态泄漏到后续请求）。统一复制副本再回填，规避扩展点实例复用契约依赖。
        GatewayInfo gatewayCopy = new GatewayInfo();
        cn.hutool.core.bean.BeanUtil.copyProperties(gateway, gatewayCopy);
        gateway = gatewayCopy;

        // ===== 注册模式目标解析：注册 Contact > 静态配置 =====
        // 注册型网关（registerEnabled=1）优先使用 REGISTER 绑定的可达地址；绑定缺失（过期/未注册）
        // 时回退静态 address:port（若配置了）；两者皆无报错阻断出局，避免发往空地址
        String targetIp = gateway.getAddress();
        Integer targetPort = gateway.getPort();
        String targetTransport = gateway.resolveSipTransport();
        if (Integer.valueOf(1).equals(gateway.getRegisterEnabled())) {
            GatewayRegisterInfo reg = gatewayRegistry.get(Long.valueOf(gatewayId));
            if (reg != null) {
                targetIp = reg.getContactIp();
                targetPort = reg.getContactPort();
                targetTransport = reg.getTransport();
                log.info("[forwardToOutboundGateway][命中注册绑定] gatewayId={}, contact={}:{}, transport={}",
                        gatewayId, targetIp, targetPort, targetTransport);
            } else if (StrUtil.isBlank(targetIp)) {
                log.error("[forwardToOutboundGateway][注册型网关未在线(无注册绑定)且未配置静态地址] gatewayId={}", gatewayId);
                throw new SipProxyException(SipProxyErrorCodeConstants.GATEWAY_NOT_FOUND,
                        "注册型网关未在线(无注册绑定)且未配置静态地址: " + gatewayId);
            } else {
                log.warn("[forwardToOutboundGateway][注册绑定缺失,回退静态配置] gatewayId={}, address={}:{}",
                        gatewayId, targetIp, targetPort);
            }
        }
        if (StrUtil.isBlank(targetIp)) {
            log.error("[forwardToOutboundGateway][网关地址为空(注册模式未注册)] gatewayId={}", gatewayId);
            throw new SipProxyException(SipProxyErrorCodeConstants.GATEWAY_NOT_FOUND,
                    "网关地址为空(注册模式未注册): " + gatewayId);
        }
        String gatewayIp = targetIp;
        int gatewayPort = targetPort != null ? targetPort : 5060;

        String callId = SipAnalysisUtil.getCallId(request);
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);

        // 出局网关腿的 transport/provider 按「注册绑定 transport > 网关配置 transport_protocol」解析
        // （注册绑定在上一段已解析; 此处直接用解析值, 与 Route 头(DefaultOutboundGatewayRewriter)
        //  同源, 避免 FS 腿切 TCP 后误随会话 transport 选错 provider 导致 408）
        String transport = targetTransport;

        modifyHeadersForForwarding(request, gatewayIp, gatewayPort, 0, transport);
        // 代理回程 IP 适配（toSipProxyIp）：出局 INVITE 的 Via/Contact 头中"代理自身地址"
        // 使用网关配置的 toSipProxyIp（可选填），告知第三方网关应向该 IP 回送响应/后续请求。
        // 不填时保持 modifyHeadersForForwarding 写入的 sip.public-ip（默认行为）。
        rewriteViaContactProxyIp(request, gateway);
        // 回填解析后的目标地址，保证 DefaultOutboundGatewayRewriter 的 Request-URI/Route
        // 改写使用注册绑定地址（扩展点接口不感知注册逻辑，网关对象由 getGatewayById 每次新建，回填安全）
        gateway.setAddress(targetIp);
        gateway.setPort(gatewayPort);
        gateway.setTransportProtocol("tcp".equalsIgnoreCase(transport) ? 2 : 1);
        rewriteForOutbound(request, gateway, gatewayId);
        // 委托 SdpProcessor 扩展点处理 SDP（默认透传，父程序可覆盖做编解码过滤等）
        Message processedMessage = sdpProcessor.process(request);
        if (processedMessage instanceof Request processedRequest) {
            request = processedRequest;
        }

        SipProvider targetProvider = SipProxyConstants.TRANSPORT_TCP.equalsIgnoreCase(transport) ? sipProviderTcp : sipProvider;
        log.info("[forwardToOutboundGateway][按网关自身协议选择发送通道] callId={}, gatewayId={}, transportProtocol={}, transport={}, provider={}",
                callId, gatewayId, gateway.getTransportProtocol(), transport,
                targetProvider == sipProviderTcp ? "TCP" : "UDP");

        if (sessionInfo != null) {
            sessionInfo.setOriginalInviteText(request.toString());
            // 缓存第三方网关节点,用于后续 Response 来源识别(解决第三方网关也是 FreeSWITCH 部署时
            // User-Agent 误识别为自身 FS 的问题,SessionInfo 上下文优先级最高)
            if (sessionInfo.getThirdPartyNode() == null) {
                sessionInfo.setThirdPartyNode(gateway);
                log.info("[forwardToOutboundGateway][已缓存第三方网关节点到SessionInfo] callId={}, gatewayId={}, gateway={}:{}",
                        callId, gatewayId, gatewayIp, gatewayPort);
            }
            sessionManager.updateSessionInfo(sessionInfo);
            log.debug("[forwardToOutboundGateway][已缓存原始INVITE文本] callId={}", callId);
        }

        try {
            targetProvider.sendRequest(request);
            log.info("[forwardToOutboundGateway][INVITE已发送到出局网关] gatewayId={}, gateway={}:{}",
                    gatewayId, gatewayIp, gatewayPort);
        } catch (Exception e) {
            log.error("[forwardToOutboundGateway][发送到出局网关失败] gatewayId={}, gateway={}:{}",
                    gatewayId, gatewayIp, gatewayPort, e);
            throw new SipProxyException(SipProxyErrorCodeConstants.FORWARD_FAILED,
                    "转发到出局网关失败: " + e.getMessage(), e);
        }
    }

    /**
     * 出局INVITE信令改写（委托给 {@link OutboundGatewayRewriter} 扩展点）
     * <p>
     * 设计意图：将出局 INVITE 头域改写逻辑（From、PAI、Record-Route 等）抽离为扩展点，
     * 默认实现 {@code DefaultOutboundGatewayRewriter} 执行标准 3 步改写，
     * 父程序可注册自定义 {@link OutboundGatewayRewriter} 覆盖。
     * <p>
     * 注意：Request-URI 已在 {@link #modifyHeadersForForwarding} 中修改为网关地址，此处不重复修改。
     *
     * @param request   出局 SIP 请求
     * @param gateway   目标网关信息
     * @param gatewayId 网关 ID（用于日志）
     */
    private void rewriteForOutbound(Request request, GatewayInfo gateway, String gatewayId) {
        log.info("[rewriteForOutbound][委托扩展点进行出局信令改写] gatewayId={}", gatewayId);
        outboundGatewayRewriter.rewrite(request, gateway);
    }

    /**
     * 出局 INVITE 的 Via/Contact 代理回程地址改写（toSipProxyIp）
     * <p>
     * 需求背景（2026-08-14）：云厂商 NAT 模式公网 IP 绑定在 lo。出局 INVITE 经
     * {@link #modifyHeadersForForwarding} 写入的 Via/Contact 均为 sip.public-ip:public-port，
     * 第三方网关按 Via 头回送响应。若代理实际只监听内网 IP（或运维希望网关从内网回程），
     * 可通过网关配置 toSipProxyIp 指定回程 IP（如 10.2.0.14），此处将 Via 顶层与 Contact 的
     * host 改写为该 IP，网关的 200 OK/后续 in-dialog 请求即发往该地址。
     * <p>
     * 约束：仅出局 INVITE（forwardToOutboundGateway 专用），不影响入局/WS 方向；
     * 未配置 toSipProxyIp（null/空）时保持 modifyHeadersForForwarding 写入的公网 IP 不变。
     *
     * @param request 出局 SIP 请求（INVITE）
     * @param gateway 目标网关信息（读取 toSipProxyIp）
     */
    private void rewriteViaContactProxyIp(Request request, GatewayInfo gateway) {
        String toIp = gateway.getToSipProxyIp();
        if (toIp == null || toIp.trim().isEmpty()) {
            return;
        }
        String callId = SipAnalysisUtil.getCallId(request);
        try {
            // Via 顶层 host 改写（响应回程地址；RFC3261 响应沿 Via 栈回传）
            ViaHeader via = (ViaHeader) request.getHeader(ViaHeader.NAME);
            if (via != null && via.getHost() != null && !toIp.equals(via.getHost())) {
                String before = via.getHost();
                via.setHost(toIp);
                log.info("[rewriteViaContactProxyIp][Via顶层host改写] callId={}, {} -> {}", callId, before, toIp);
            }
            // Contact host 改写（in-dialog 请求如 ACK/BYE 回程地址）
            ContactHeader contact = (ContactHeader) request.getHeader(ContactHeader.NAME);
            if (contact != null && contact.getAddress() != null
                    && contact.getAddress().getURI() instanceof SipURI sipUri) {
                if (!toIp.equals(sipUri.getHost())) {
                    String before = sipUri.getHost();
                    sipUri.setHost(toIp);
                    log.info("[rewriteViaContactProxyIp][Contact host改写] callId={}, {} -> {}", callId, before, toIp);
                }
            }
        } catch (Exception e) {
            log.warn("[rewriteViaContactProxyIp][改写失败,保留原地址] callId={}, toSipProxyIp={}", callId, toIp, e);
        }
    }

    /**
     * 处理407 Proxy Authentication Required响应（委托给GatewayAuthManager）
     *
     * @param response    407响应
     * @param sessionInfo 会话信息
     * @return true=重发成功（拦截407），false=无法重试（转发407）
     */
    public boolean handle407ProxyAuth(Response response, SessionInfo sessionInfo) {
        return gatewayAuthManager.handle407Challenge(response, sessionInfo);
    }
}
