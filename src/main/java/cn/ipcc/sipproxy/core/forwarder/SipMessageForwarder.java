package cn.ipcc.sipproxy.core.forwarder;

import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.api.gateway.OutboundGatewayRewriter;
import cn.ipcc.sipproxy.api.media.SdpProcessor;
import cn.ipcc.sipproxy.autoconfigure.SipProxyProperties;
import cn.ipcc.sipproxy.core.auth.GatewayAuthManager;
import cn.ipcc.sipproxy.core.node.SipNodeManager;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.session.SipSessionManager;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.SipProxyErrorCodeConstants;
import cn.ipcc.sipproxy.support.SipProxyException;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
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

    @Resource
    private OutboundGatewayRewriter outboundGatewayRewriter;

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
        List<FsNodeInfo> triedNodes = new ArrayList<>();
        FsNodeInfo currentNode = node;
        while (true) {
            triedNodes.add(currentNode);
            log.info("[forwardToFreeSwitch][第{}次尝试] fs={}:{}",
                    triedNodes.size(), currentNode.getSipIp(), currentNode.getSipPort());

            try {
                Message modifiedMessage = modifyHeadersForForwarding(message, currentNode.getSipIp(), currentNode.getSipPort(), triedNodes.size());
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
     * 实际的FreeSWITCH转发逻辑（无故障转移）
     *
     * @param message SIP消息
     * @param node    目标FS节点
     */
    private void doForwardToFreeSwitch(Message message, FsNodeInfo node) throws Exception {
        log.info("[doForwardToFreeSwitch][开始转发消息到FreeSWITCH] fs={}:{}, message={}",
                node.getSipIp(), node.getSipPort(), message.getClass().getSimpleName());

        String callId = SipAnalysisUtil.getCallId(message);
        String transport = SipProxyConstants.TRANSPORT_UDP;
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo != null && sessionInfo.getToSipTransport() != null) {
            transport = sessionInfo.getToSipTransport();
        }

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
                targetProvider.sendResponse(response);
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
        log.info("[forwardToThirdParty][开始转发消息到第三方SIP服务] tp={}:{}, message={}",
                node.getAddress(), node.getPort(), message.getClass().getSimpleName());

        String callId = SipAnalysisUtil.getCallId(message);
        String transport = SipProxyConstants.TRANSPORT_UDP;
        if (callId != null) {
            SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
            if (sessionInfo != null && sessionInfo.getToSipTransport() != null) {
                transport = sessionInfo.getToSipTransport();
            }
        }

        SipProvider targetProvider = SipProxyConstants.TRANSPORT_TCP.equalsIgnoreCase(transport) ? sipProviderTcp : sipProvider;
        log.info("[forwardToThirdParty][选择传输协议] transport={}", transport);

        try {
            String targetIp = node.getAddress();
            Integer targetPort = node.getPort() != null ? node.getPort() : 5060;
            Message modifiedMessage = modifyHeadersForForwarding(message, targetIp, targetPort, 0);
            // 委托 SdpProcessor 扩展点处理 SDP（默认透传，父程序可覆盖做 ICE 候选替换等）
            modifiedMessage = sdpProcessor.process(modifiedMessage);
            if (modifiedMessage instanceof Request request) {
                targetProvider.sendRequest(request);
                log.info("[forwardToThirdParty][请求已发送到第三方SIP服务] method={}, tp={}:{}",
                        request.getMethod(), targetIp, targetPort);
            } else if (message instanceof Response response) {
                log.info("[forwardToThirdParty][响应已发送到第三方SIP服务] statusCode={}, tp={}:{}",
                        response.getStatusCode(), targetIp, targetPort);
                targetProvider.sendResponse(response);
            }
        } catch (Exception e) {
            log.error("[forwardToThirdParty][转发消息到第三方SIP服务失败] tp={}:{}",
                    node.getAddress(), node.getPort(), e);
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
     * 修改SIP消息头以便正确转发到FreeSWITCH或第三方SIP服务
     */
    public Message modifyHeadersForForwarding(Message message, String targetIp, Integer targetPort, int attemptCount)
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
            if (sessionInfo.getToSipTransport() != null) {
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
        String newSdpBody = sdpBody.replace(currentIp, fsPublicIp);
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

        String gatewayIp = gateway.getAddress();
        int gatewayPort = gateway.getPort() != null ? gateway.getPort() : 5060;

        String callId = SipAnalysisUtil.getCallId(request);
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);

        modifyHeadersForForwarding(request, gatewayIp, gatewayPort, 0);
        rewriteForOutbound(request, gateway, gatewayId);
        // 委托 SdpProcessor 扩展点处理 SDP（默认透传，父程序可覆盖做编解码过滤等）
        Message processedMessage = sdpProcessor.process(request);
        if (processedMessage instanceof Request processedRequest) {
            request = processedRequest;
        }

        String transport = "udp";
        if (sessionInfo != null && sessionInfo.getToSipTransport() != null) {
            transport = sessionInfo.getToSipTransport();
        }
        SipProvider targetProvider = "tcp".equalsIgnoreCase(transport) ? sipProviderTcp : sipProvider;

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
