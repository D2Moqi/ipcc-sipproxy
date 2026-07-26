package cn.ipcc.sipproxy.core.forwarder;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.ipcc.sipproxy.autoconfigure.SipProxyProperties;
import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.core.node.SipNodeManager;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.session.SipSessionManager;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.GatewayTypeEnum;
import cn.ipcc.sipproxy.support.SipProxyErrorCodeConstants;
import cn.ipcc.sipproxy.support.SipProxyException;
import cn.ipcc.sipproxy.support.SipProxyConstants;
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
import javax.sip.header.CSeqHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ProxyAuthenticateHeader;
import javax.sip.header.UserAgentHeader;
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
 * @author 芋道源码
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
    private SipProxyProperties properties;

    @Resource
    private GatewayProvider gatewayProvider;

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

    public void forwardToWebSocket(String sessionId, Message message) throws Exception {
        log.debug("[forwardToWebSocket][转发SIP消息到WebSocket客户端] sessionId={}", sessionId);
        wsSessionManager.send(sessionId, message.toString());
    }

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
        // 记录已尝试的节点
        List<FsNodeInfo> triedNodes = new ArrayList<>();
        FsNodeInfo currentNode = node;
        while (true) {
            triedNodes.add(currentNode);
            log.info("[forwardToFreeSwitch][第{}次尝试] fs={}:{}",
                    triedNodes.size(), currentNode.getSipIp(), currentNode.getSipPort());

            try {
                Message modifiedMessage = modifyHeadersForForwarding(message, currentNode, triedNodes.size());
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
     * 需求：将SIP消息发送到指定的FreeSWITCH节点
     * 预期结果：消息通过选定的传输协议（UDP/TCP）成功发送到FreeSWITCH
     * 处理逻辑：
     * 1. 根据会话信息确定传输协议（默认UDP）
     * 2. 选择对应的SipProvider发送请求或响应
     * 3. 自定义头域（如X-Gateway-Id）在转发过程中自动保留，
     *    FreeSWITCH收到后会自动将X-开头的SIP头转为sip_h_X-xxx channel变量
     */
    private void doForwardToFreeSwitch(Message message, FsNodeInfo node) throws Exception {
        log.info("[doForwardToFreeSwitch][开始转发消息到FreeSWITCH] fs={}:{}, message={}",
                node.getSipIp(), node.getSipPort(), message.getClass().getSimpleName());

        String callId = SipAnalysisUtil.getCallId(message);
        String transport = "udp";
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo != null && sessionInfo.getToSipTransport() != null) {
            transport = sessionInfo.getToSipTransport();
        }

        SipProvider targetProvider = "tcp".equalsIgnoreCase(transport) ? sipProviderTcp : sipProvider;
        log.info("[doForwardToFreeSwitch][选择传输协议] transport={}, provider={}", transport,
                targetProvider == sipProviderTcp ? "TCP" : "UDP");

        try {
            if (message instanceof Request request) {
                // 确认X-Gateway-Id自定义头域在转发时被保留
                Header gatewayIdHeader = request.getHeader("X-Gateway-Id");
                if (gatewayIdHeader != null) {
                    log.info("[doForwardToFreeSwitch][X-Gateway-Id头域已保留] callId={}, X-Gateway-Id={}",
                            callId, gatewayIdHeader);
                }
                log.info("[doForwardToFreeSwitch][请求发送到FreeSWITCH] method={}, fs={}:{}, 发送的request信息={}",
                        request.getMethod(), node.getSipIp(), node.getSipPort(), request.toString());
                targetProvider.sendRequest(request);
                log.info("[doForwardToFreeSwitch][请求已发送到FreeSWITCH]");
            } else if (message instanceof Response response) {
                log.info("[doForwardToFreeSwitch][响应发送到FreeSWITCH] statusCode={}, fs={}:{}, 发送的response信息={}",
                        response.getStatusCode(), node.getSipIp(), node.getSipPort(), response.toString());
                targetProvider.sendResponse(response);
                log.info("[doForwardToFreeSwitch][请求已发送到FreeSWITCH]");
            }
        } catch (Exception e) {
            log.error("[doForwardToFreeSwitch][转发消息到FreeSWITCH失败] fs={}:{}",
                    node.getSipIp(), node.getSipPort(), e);
            throw new SipProxyException(SipProxyErrorCodeConstants.FORWARD_FAILED,
                    "转发消息到FreeSWITCH失败: " + e.getMessage(), e);
        }
    }

    public void forwardToThirdParty(Message message, FsNodeInfo node) throws Exception {
        log.info("[forwardToThirdParty][开始转发消息到第三方SIP服务] tp={}:{}, message={}",
                node.getSipIp(), node.getSipPort(), message.getClass().getSimpleName());

        String callId = SipAnalysisUtil.getCallId(message);
        String transport = "udp";
        if (callId != null) {
            SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
            if (sessionInfo != null && sessionInfo.getToSipTransport() != null) {
                transport = sessionInfo.getToSipTransport();
            }
        }

        SipProvider targetProvider = "tcp".equalsIgnoreCase(transport) ? sipProviderTcp : sipProvider;
        log.info("[forwardToThirdParty][选择传输协议] transport={}, provider={}", transport,
                targetProvider == sipProviderTcp ? "TCP" : "UDP");

        try {
            Message modifiedMessage = modifyHeadersForForwarding(message, node, 0);
            if (modifiedMessage instanceof Request request) {
                targetProvider.sendRequest(request);
                log.info("[forwardToThirdParty][请求已发送到第三方SIP服务] method={}, tp={}:{}",
                        request.getMethod(), node.getSipIp(), node.getSipPort());
            } else if (message instanceof Response response) {
                log.info("[forwardToThirdParty][响应已发送到第三方SIP服务] statusCode={}, tp={}:{}",
                        response.getStatusCode(), node.getSipIp(), node.getSipPort());
                targetProvider.sendResponse(response);
            }
        } catch (Exception e) {
            log.error("[forwardToThirdParty][转发消息到第三方SIP服务失败] tp={}:{}",
                    node.getSipIp(), node.getSipPort(), e);
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
        forwardToWebSocket(sessionId, modifiedMessage);
        log.info("[forwardToWebSocketByUser][请求已转发到WebSocket客户端] sessionId={}, username={}", sessionId, username);
    }

    /**
     * 修改SIP消息头以便正确转发到FreeSWITCH或第三方SIP服务
     * <p>
     * 需求：SIP代理转发消息时，Contact头和Via头中的地址需要替换为SIP代理自身的公网地址，
     * 使得后续SIP信令能正确路由回本代理。
     * <p>
     * 预期结果：消息中的Contact头、Via头被替换为配置的SIP代理公网IP和端口，
     * Request-URI被修改为目标节点地址，返回修改后的消息。
     * <p>
     * 处理逻辑：
     * 1. 根据Call-ID获取会话信息，无会话则跳过处理；
     * 2. 从 SipProxyProperties 读取 SIP 代理公网 IP 和端口配置；
     * 3. 替换Contact头为SIP代理公网地址；
     * 4. 对于Request：替换Via头为SIP代理公网地址，并修改Request-URI为目标节点地址；
     * 5. 对于Response：替换Via头为目标节点地址（响应需回送到来源节点）。
     *
     * @param message      SIP消息（请求或响应）
     * @param targetNode   目标FreeSWITCH/第三方节点
     * @param attemptCount 转发尝试次数（用于故障转移场景）
     * @return 修改后的SIP消息
     * @throws Exception 修改SIP头失败时抛出
     */
    public Message modifyHeadersForForwarding(Message message, FsNodeInfo targetNode, int attemptCount)
            throws Exception {
        log.debug("[modifyHeadersForForwarding][开始修改SIP头] message={}, target={}:{}, attemptCount={}",
                message.getClass().getSimpleName(), targetNode.getSipIp(), targetNode.getSipPort(), attemptCount);
        String callId = SipAnalysisUtil.getCallId(message);
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo == null) {
            log.warn("[modifyHeadersForForwarding][未找到会话信息，跳过sip信息处理] callId={}", callId);
            return message;
        }
        // 获取事务id
        String branchId = SipAnalysisUtil.getBranch(message);
        try {
            String transport = "udp";
            if (sessionInfo.getToSipTransport() != null) {
                transport = sessionInfo.getToSipTransport();
            }

            // 读取SIP代理公网地址配置，用于替换Contact/Via头中的地址
            String sipProxyPublicIp = properties.getSip().getPublicIp();
            int sipProxyPublicPort = properties.getSip().getPublicPort();

            try {
                ContactHeader contactHeader = (ContactHeader) message.getHeader(ContactHeader.NAME);
                if (contactHeader != null) {
                    String contactHost = sipProxyPublicIp;
                    int contactPort = sipProxyPublicPort;
                    SipURI contactUri = addressFactory.createSipURI(null, contactHost);
                    contactUri.setPort(contactPort);
                    // 使用 setParameter 绕过 JAIN-SIP 1.2.1.4 对 ws/wss 传输参数的校验
                    // （JAIN-SIP 不识别 RFC 7118 的 ws/wss），生成的 wire 格式 transport=ws 完全一致
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
                    log.info("[modifyHeadersForForwarding][已缓存Via头信息] callId={}", callId);
                } catch (Exception e) {
                    log.error("[modifyHeadersForForwarding][处理Via头失败] callId={}", callId, e);
                }

                // 保留原始Request-URI的user部分(被叫号码),仅替换host和port为目标FS节点地址
                // 修复说明: 若user传null,FS收到的Request-URI为"nobody@fs:port",
                //   FS无法按被叫号码匹配dialplan extension,导致呼叫进入默认context执行错误应用(如record)
                String originalUser = null;
                try {
                    javax.sip.address.URI originalUri = request.getRequestURI();
                    if (originalUri instanceof SipURI originalSipUri) {
                        originalUser = originalSipUri.getUser();
                    }
                } catch (Exception e) {
                    log.warn("[modifyHeadersForForwarding][提取原始Request-URI user失败] callId={}", callId, e);
                }
                SipURI requestUri = addressFactory.createSipURI(originalUser, targetNode.getSipIp());
                requestUri.setPort(targetNode.getSipPort());
                request.setRequestURI(requestUri);
                log.debug("[modifyHeadersForForwarding][已修改Request-URI] user={}, uri={}:{}",
                        originalUser, targetNode.getSipIp(), targetNode.getSipPort());
            } else if (message instanceof Response response) {
                try {
                    ViaHeader viaHeader = headerFactory.createViaHeader(
                            targetNode.getSipIp(),
                            targetNode.getSipPort(),
                            transport,
                            null);
                    viaHeader.setBranch(branchId);
                    viaHeader.setRPort();
                    response.removeHeader(ViaHeader.NAME);
                    response.addHeader(viaHeader);
                    log.debug("[modifyHeadersForForwarding][response已添加Via头] via={}:{}, transport={}", targetNode.getSipIp(),
                            targetNode.getSipPort(), transport);

                } catch (Exception e) {
                    log.error("[modifyHeadersForForwarding][response处理Via头失败] callId={}", callId, e);
                }
            }

            // 头域改写完成后，校验SDP中ICE候选完整性（仅告警不阻断，FS会处理ICE协商）
            validateIceCandidateInSdp(message);
            return message;
        } catch (Exception e) {
            log.error("[modifyHeadersForForwarding][修改SIP头失败]", e);
            throw e;
        }
    }

    /**
     * 校验SDP中ICE候选的完整性
     * <p>
     * 业务背景：WebRTC场景下，SDP应包含完整的ICE候选（a=candidate行）以保证媒体协商成功。
     * 若SDP声明了ICE支持（包含a=ice-ufrag）但未提供任何ICE候选（a=candidate），
     * 可能导致对端无法建立媒体连接，影响通话媒体协商。
     * <p>
     * 校验规则：仅对Request类型消息（INVITE/UPDATE/PRACK可能携带SDP）进行校验，
     * 且Content-Type必须为application/sdp。当SDP中存在 a=ice-ufrag 但缺失 a=candidate 时告警；
     * 其他情况（两者都有、两者都无、有candidate无ice-ufrag）均不告警。
     * <p>
     * 处理策略：仅告警不阻断转发流程，FreeSWITCH作为ICE Lite端会自行处理ICE协商，
     * 即使本地候选缺失也可能通过后续协商或媒体重协商恢复，故不在此处阻断。
     * <p>
     * 触发时机：由 modifyHeadersForForwarding 末尾调用，此时消息头域已改写完成，
     * 校验的是最终转发到FreeSWITCH/第三方SIP的消息内容。
     *
     * @param message SIP消息（仅Request类型且Content-Type为application/sdp时才进行校验）
     */
    private void validateIceCandidateInSdp(Message message) {
        // 仅对Request类型消息校验（INVITE/UPDATE/PRACK可能携带SDP）
        if (!(message instanceof Request)) {
            return;
        }
        // 检查Content-Type头是否为application/sdp，无Content-Type或非SDP直接返回
        ContentTypeHeader contentTypeHeader = (ContentTypeHeader) message.getHeader(ContentTypeHeader.NAME);
        if (contentTypeHeader == null) {
            return;
        }
        if (!"application".equals(contentTypeHeader.getContentType())
                || !"sdp".equals(contentTypeHeader.getContentSubType())) {
            return;
        }
        // 提取消息体（SDP内容），为空则跳过校验
        Object content = message.getContent();
        if (content == null) {
            return;
        }
        String sdpBody = content.toString();
        if (sdpBody.isEmpty()) {
            return;
        }
        // 检查SDP中是否包含ICE相关字段：ice-ufrag声明ICE支持，candidate提供实际候选地址
        boolean hasIceUfrag = sdpBody.contains("a=ice-ufrag");
        boolean hasCandidate = sdpBody.contains("a=candidate");
        // 声明了ICE支持但未提供候选，可能影响媒体协商，记录告警
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
                // 使用 setParameter 绕过 JAIN-SIP 1.2.1.4 对 ws 传输参数的校验（RFC 7118）
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
                SipURI requestUri = null;
                if (sessionInfo.getWebsocketContactIp() != null) {
                    requestUri = addressFactory.createSipURI(sessionInfo.getWebsocketContactName(),
                            sessionInfo.getWebsocketContactIp());
                    requestUri.setPort(sessionInfo.getWebsocketContactPort());
                    // 使用 setParameter 绕过 JAIN-SIP 1.2.1.4 对 ws 传输参数的校验（RFC 7118）
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
                    // 使用 setParameter 绕过 JAIN-SIP 1.2.1.4 对 ws 传输参数的校验（RFC 7118）
                    requestUri.setParameter(SipProxyConstants.TRANSPORT_PARAM, transport);
                }
                request.setRequestURI(requestUri);
                log.info("[modifyWsProxyHeaders][已修改Request-URI为WebSocket地址] callId={}, newRequestUri={}", callId,
                        requestUri);
            } catch (Exception e) {
                log.error("[modifyWsProxyHeaders][修改Request-URI失败] callId={}", callId, e);
            }
        }

        // 修改SDP中的FS媒体地址: FS使用host网络模式时SDP声明的是宿主机内网IP,JsSIP无法访问,需替换为FS公网IP
        modifySdpForWebSocket(message, sessionInfo, callId);

        return message;
    }

    /**
     * 修改转发到WebSocket的SIP消息中的SDP媒体地址
     * <p>
     * 业务背景: FS容器使用host网络模式时,SDP中的 c= 行和 a=candidate 行声明的是宿主机内网IP
     * (如10.2.0.14),而JsSIP客户端通过公网访问FS,无法访问内网IP,导致ICE协商和DTLS-SRTP握手失败。
     * <p>
     * 处理策略: 从SessionInfo获取FS节点配置的公网IP(FsNodeInfo.sipIp),提取SDP中 c= 行声明的IP,
     * 若两者不一致则将SDP中所有该IP替换为公网IP。替换范围包括:
     *   - c=IN IP4 (连接信息行)
     *   - o= (Origin行,包含IN IP4)
     *   - a=candidate (ICE候选行)
     * <p>
     * 触发时机: modifyWsProxyHeaders末尾调用,仅对转发到WebSocket( JsSIP客户端)的消息生效,
     * 不影响转发到FS或第三方网关的SDP。
     *
     * @param message     SIP消息(请求或响应)
     * @param sessionInfo 会话信息,用于获取FS节点配置
     * @param callId      Call-ID,用于日志追踪
     */
    private void modifySdpForWebSocket(Message message, SessionInfo sessionInfo, String callId) {
        // 仅处理包含SDP的消息: Content-Type必须为application/sdp
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
        // 提取SDP消息体,为空则跳过
        // JAIN SIP的getContent()可能返回String或byte[],需统一转换为String
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
        // 从SessionInfo获取FS节点配置的公网IP(FsNodeInfo.sipIp)
        FsNodeInfo fsNode = sessionInfo.getFreeSwitchNode();
        if (fsNode == null || fsNode.getSipIp() == null || fsNode.getSipIp().isEmpty()) {
            log.warn("[modifySdpForWebSocket][SessionInfo中freeSwitchNode为null或ip为空,无法替换SDP] callId={}, fsNode={}",
                    callId, fsNode == null ? "null" : fsNode.getSipIp());
            return;
        }
        String fsPublicIp = fsNode.getSipIp();
        // 从SDP的 c= 行提取当前声明的IP地址
        Pattern pattern = Pattern.compile("c=IN IP4 ([0-9.]+)");
        Matcher matcher = pattern.matcher(sdpBody);
        if (!matcher.find()) {
            log.debug("[modifySdpForWebSocket][SDP中未找到c=IN IP4行,跳过] callId={}", callId);
            return;
        }
        String currentIp = matcher.group(1);
        // SDP中声明的IP与FS公网IP一致则无需替换
        if (currentIp.equals(fsPublicIp)) {
            log.debug("[modifySdpForWebSocket][SDP中IP与FS公网IP一致,无需替换] callId={}, ip={}", callId, currentIp);
            return;
        }
        log.info("[modifySdpForWebSocket][替换SDP中的FS媒体地址] callId={}, oldIp={}, newIp={}",
                callId, currentIp, fsPublicIp);
        // 将SDP中所有该IP替换为公网IP(c=行、o=行、a=candidate行)
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
     * 需求: 当SIP代理检测到INVITE携带X-Gateway-Id(出局呼叫),需执行信令改写后转发到第三方网关
     * 预期结果: INVITE经信令改写后成功发送到目标网关
     * 处理逻辑:
     *   1. 查询网关配置获取目标网关地址
     *   2. 执行标准头域改写(Via/Contact)
     *   3. 执行出局信令改写(Request-URI/From/PAI/认证/拓扑隐藏)
     *   4. 发送请求到目标网关
     *
     * Timer B 配置说明:
     *   - 优先级: gateway.timerB > 默认 32000ms
     *   - 当前限制: JAIN SIP 运行时不支持按事务动态调整 Timer B,仅记录日志用于追踪
     *   - 实际 Timer B 由 SipStack 全局配置决定,需在 stack 初始化阶段设置
     *
     * @param request   SIP INVITE请求
     * @param gatewayId 网关ID
     */
    public void forwardToOutboundGateway(Request request, String gatewayId) throws Exception {
        log.info("[forwardToOutboundGateway][开始转发到出局网关] gatewayId={}", gatewayId);

        // 通过 GatewayProvider 扩展点查询网关配置
        GatewayInfo gateway = gatewayProvider.getGatewayById(gatewayId);
        if (gateway == null) {
            log.error("[forwardToOutboundGateway][网关不存在] gatewayId={}", gatewayId);
            throw new SipProxyException(SipProxyErrorCodeConstants.GATEWAY_NOT_FOUND,
                    "网关不存在: " + gatewayId);
        }


        // 校验网关类型必须为EXTERNAL,防止转发到内部FS导致回环死循环
        if (!GatewayTypeEnum.EXTERNAL.getType().equals(gateway.getType())) {
            log.error("[forwardToOutboundGateway][网关类型无效,禁止转发到内部网关] gatewayId={}, type={}",
                    gatewayId, gateway.getType());
            throw new SipProxyException(SipProxyErrorCodeConstants.GATEWAY_TYPE_INVALID,
                    "网关类型无效，禁止转发到内部网关");
        }

        // 解析网关代理地址
        String[] proxyParts = parseGatewayProxy(gateway.getProxy());
        String gatewayIp = proxyParts[0];
        int gatewayPort = Integer.parseInt(proxyParts[1]);

        FsNodeInfo targetNode = new FsNodeInfo();
        targetNode.setSipIp(gatewayIp);
        targetNode.setSipPort(gatewayPort);

        // 执行标准头域改写(Via/Contact)
        modifyHeadersForForwarding(request, targetNode, 0);

        // 执行出局信令改写(Request-URI/From/PAI/认证/拓扑隐藏)
        rewriteForOutbound(request, gatewayId);

        // 发送请求到目标网关
        String callId = SipAnalysisUtil.getCallId(request);
        String transport = "udp";
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo != null && sessionInfo.getToSipTransport() != null) {
            transport = sessionInfo.getToSipTransport();
        }
        SipProvider targetProvider = "tcp".equalsIgnoreCase(transport) ? sipProviderTcp : sipProvider;

        // 缓存出局INVITE请求内容（已完成全部信令改写），用于收到407鉴权挑战时重建Request重发
        if (sessionInfo != null) {
            sessionInfo.setOriginalInviteContent(request.toString());
            sessionManager.updateSessionInfo(sessionInfo);
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
     * 出局INVITE信令改写
     *
     * 需求: SIP代理向第三方网关转发INVITE时,需改写信令以适配网关要求并隐藏内部拓扑
     * 预期结果: Request-URI地址改为网关地址(号码不变),From改为DID,注入PAI和认证,移除内部Via
     * 处理逻辑:
     *   1. 查询网关ID表获取目标网关配置
     *   2. 改写Request-URI地址部分为网关IP:端口
     *   3. 改写From头为对外DID号码
     *   4. 注入P-Asserted-Identity头
     *   5. 注入Authorization认证(若网关需注册)
     *   6. 移除暴露内部FS地址的Via/Record-Route头域
     *
     * @param request   SIP INVITE请求
     * @param gatewayId 网关ID
     */
    private void rewriteForOutbound(Request request, String gatewayId) throws Exception {
        // 1. 查询网关配置
        GatewayInfo gateway = gatewayProvider.getGatewayById(gatewayId);
        if (gateway == null) {
            log.error("[rewriteForOutbound][网关不存在] gatewayId={}", gatewayId);
            throw new SipProxyException(SipProxyErrorCodeConstants.GATEWAY_NOT_FOUND,
                    "网关不存在: " + gatewayId);
        }

        // 解析网关代理地址
        String[] proxyParts = parseGatewayProxy(gateway.getProxy());
        String gatewayIp = proxyParts[0];
        int gatewayPort = Integer.parseInt(proxyParts[1]);

        String callId = SipAnalysisUtil.getCallId(request);
        log.info("[rewriteForOutbound][开始出局信令改写] callId={}, gatewayId={}, gatewayIp={}:{}",
                callId, gatewayId, gatewayIp, gatewayPort);

        // 2. 改写Request-URI地址部分为网关IP:端口(保留被叫号码不变)
        URI originalUri = request.getRequestURI();
        String calledNumber = null;
        if (originalUri instanceof SipURI sipUri) {
            calledNumber = sipUri.getUser();
        }
        SipURI newRequestUri = addressFactory.createSipURI(calledNumber, gatewayIp);
        newRequestUri.setPort(gatewayPort);
        request.setRequestURI(newRequestUri);
        log.info("[rewriteForOutbound][已改写Request-URI] callId={}, calledNumber={}, newUri={}",
                callId, calledNumber, newRequestUri);

        // 3. 改写From头为对外DID号码
        // callerIdInFrom: 0=在From中使用原始主叫号码, 1=使用DID号码(默认)
        String originalCaller = SipAnalysisUtil.extractFromUser(request);
        String fromDomain = gateway.getFromDomain();
        if (fromDomain == null || fromDomain.isEmpty()) {
            fromDomain = gatewayIp;
        }

        boolean useCallerIdInFrom = gateway.getCallerIdInFrom() != null && gateway.getCallerIdInFrom() == 0;
        String fromNumber = useCallerIdInFrom ? originalCaller : gateway.getExternalLineNumber();

        if (fromNumber != null && !fromNumber.isEmpty()) {
            SipURI fromUri = addressFactory.createSipURI(fromNumber, fromDomain);
            Address fromAddress = addressFactory.createAddress(fromUri);
            FromHeader newFromHeader = headerFactory.createFromHeader(fromAddress, null);
            request.removeHeader(FromHeader.NAME);
            request.addHeader(newFromHeader);
            log.info("[rewriteForOutbound][已改写From头] callId={}, fromNumber={}, fromDomain={}, useCallerIdInFrom={}",
                    callId, fromNumber, fromDomain, useCallerIdInFrom);
        }

        // 4. 注入P-Asserted-Identity头(携带真实主叫号码供运营商鉴权)
        if (originalCaller != null && !originalCaller.isEmpty()) {
            SipURI paiUri = addressFactory.createSipURI(originalCaller, fromDomain);
            Address paiAddress = addressFactory.createAddress(paiUri);
            Header paiHeader = headerFactory.createHeader("P-Asserted-Identity",
                    "<" + paiAddress.toString() + ">");
            request.removeHeader("P-Asserted-Identity");
            request.addHeader(paiHeader);
            log.info("[rewriteForOutbound][已注入P-Asserted-Identity] callId={}, pai={}", callId, paiHeader);
        }

        // 5. 注入Authorization认证(若网关需注册)
        if (gateway.getRegister() != null && gateway.getRegister() == 1
                && gateway.getUsername() != null && !gateway.getUsername().isEmpty()) {
            String realm = gateway.getRealm() != null ? gateway.getRealm() : gatewayIp;
            String authValue = String.format(
                    "Digest username=\"%s\", realm=\"%s\", nonce=\"\", uri=\"sip:%s:%d\", response=\"\"",
                    gateway.getUsername(), realm, gatewayIp, gatewayPort);
            Header authHeader = headerFactory.createHeader("Authorization", authValue);
            request.removeHeader("Authorization");
            request.addHeader(authHeader);
            log.info("[rewriteForOutbound][已注入Authorization] callId={}, username={}, realm={}",
                    callId, gateway.getUsername(), realm);
        }

        // 6. 移除暴露内部FS地址的Record-Route头域(拓扑隐藏)
        // Via头已在modifyHeadersForForwarding中替换为SIP代理公网地址,此处移除Record-Route
        request.removeHeader("Record-Route");
        log.info("[rewriteForOutbound][已移除Record-Route头域] callId={}", callId);

        log.info("[rewriteForOutbound][出局信令改写完成] callId={}, gatewayId={}", callId, gatewayId);
    }

    /**
     * 解析网关代理地址
     *
     * 需求: 网关proxy字段可能是多种格式,需统一解析为IP和端口
     * 预期结果: 返回String数组,[0]=IP,[1]=端口字符串
     * 处理逻辑:
     *   1. 去除sip:前缀(如有)
     *   2. 分离IP和端口
     *   3. 无端口时默认5060
     *
     * @param proxy 网关代理地址
     * @return [IP, 端口]
     */
    private String[] parseGatewayProxy(String proxy) {
        if (proxy == null || proxy.isEmpty()) {
            throw new IllegalArgumentException("网关代理地址不能为空");
        }
        String address = proxy.trim();
        // 去除sip:前缀
        if (address.toLowerCase().startsWith("sip:")) {
            address = address.substring(4);
        }
        // 分离IP和端口
        int colonIndex = address.lastIndexOf(':');
        if (colonIndex > 0) {
            return new String[]{address.substring(0, colonIndex), address.substring(colonIndex + 1)};
        }
        // 无端口,默认5060
        return new String[]{address, "5060"};
    }

    /**
     * 处理407 Proxy Authentication Required响应，注入鉴权重发INVITE
     *
     * <p>业务背景：SIP代理作为B2BUA向第三方网关转发INVITE后，网关可能返回407 Proxy Authentication
     * Required要求代理鉴权（RFC 3261 21.4节）。此时代理需根据网关配置的账号密码计算Digest摘要
     * （RFC 2617），构造Proxy-Authorization头注入到原INVITE并重发。</p>
     *
     * <p>处理流程：
     * <ol>
     *   <li>循环防护：检查authRetried标记，已重试过则拒绝（重发上限1次，避免凭证错误导致407死循环）</li>
     *   <li>参数提取：从407响应的Proxy-Authenticate头解析realm、nonce、algorithm（默认MD5）</li>
     *   <li>网关校验：通过SessionInfo.gatewayId查询GatewayInfo，校验userName/password非空</li>
     *   <li>请求重建：优先使用传入的originalInvite，否则从SessionInfo.originalInviteContent解析重建</li>
     *   <li>Digest计算（RFC 2617）：
     *     <pre>HA1 = MD5(userName:realm:password)
     *HA2 = MD5("INVITE":requestURI)
     *response = MD5(HA1:nonce:HA2)</pre>
     *   </li>
     *   <li>头域注入：构造Proxy-Authorization头并注入到INVITE请求</li>
     *   <li>事务更新：递增CSeq序号、生成新Via branch（RFC 3261新事务要求）</li>
     *   <li>标记重试：sessionInfo.setAuthRetried(true)并更新Redis缓存</li>
     *   <li>重发请求：按会话传输协议选择SipProvider发送</li>
     * </ol></p>
     *
     * <p>异常场景与返回值：
     * <ul>
     *   <li>已重试过（authRetried=true）→ 返回false，记录error日志</li>
     *   <li>gatewayId为空 → 返回false（非出局呼叫，无网关可鉴权）</li>
     *   <li>407响应无Proxy-Authenticate头 → 返回false</li>
     *   <li>网关不存在/未配置userName或password → 返回false</li>
     *   <li>无法获取原始INVITE（originalInvite和缓存均为空）→ 返回false</li>
     *   <li>Digest计算/头域注入/发送过程异常 → 返回false，记录error日志</li>
     *   <li>重发成功 → 返回true（调用方据此拦截407不转发到坐席）</li>
     * </ul></p>
     *
     * @param response      第三方网关返回的407响应
     * @param sessionInfo   当前会话信息（需含gatewayId和originalInviteContent）
     * @param originalInvite 原始INVITE请求对象，可为null（为null时从sessionInfo.originalInviteContent解析重建）
     * @return true=鉴权注入并重发成功；false=不可重试或重发失败（应正常转发407让坐席感知错误）
     */
    public boolean handle407ProxyAuth(Response response, SessionInfo sessionInfo, Request originalInvite) {
        String callId = sessionInfo.getCallId();
        try {
            // 1. 循环防护：已重试过则拒绝，避免凭证错误导致407无限循环
            if (sessionInfo.isAuthRetried()) {
                log.error("[handle407ProxyAuth][已重试过鉴权，禁止再次重试避免407循环] callId={}", callId);
                return false;
            }

            // 2. 校验gatewayId非空（仅出局呼叫携带X-Gateway-Id的会话才可能触发407处理）
            String gatewayId = sessionInfo.getGatewayId();
            if (gatewayId == null || gatewayId.isEmpty()) {
                log.error("[handle407ProxyAuth][gatewayId为空，无法获取网关认证信息] callId={}", callId);
                return false;
            }

            // 3. 从407响应的Proxy-Authenticate头提取Digest挑战参数
            ProxyAuthenticateHeader proxyAuthHeader =
                    (ProxyAuthenticateHeader) response.getHeader(ProxyAuthenticateHeader.NAME);
            if (proxyAuthHeader == null) {
                log.error("[handle407ProxyAuth][407响应未包含Proxy-Authenticate头] callId={}", callId);
                return false;
            }
            String realm = proxyAuthHeader.getRealm();
            String nonce = proxyAuthHeader.getNonce();
            String algorithm = proxyAuthHeader.getAlgorithm();
            if (algorithm == null || algorithm.isEmpty()) {
                algorithm = "MD5";
            }
            log.info("[handle407ProxyAuth][提取鉴权参数] callId={}, realm={}, algorithm={}", callId, realm, algorithm);

            // 4. 通过 GatewayProvider 扩展点查询网关配置获取鉴权凭证
            GatewayInfo gateway = gatewayProvider.getGatewayById(gatewayId);
            if (gateway == null) {
                log.error("[handle407ProxyAuth][网关不存在] gatewayId={}", gatewayId);
                return false;
            }
            String userName = gateway.getUsername();
            String password = gateway.getPassword();
            if (userName == null || userName.isEmpty() || password == null || password.isEmpty()) {
                log.error("[handle407ProxyAuth][网关未配置userName或password，无法计算Digest] gatewayId={}", gatewayId);
                return false;
            }

            // 5. 获取INVITE请求对象：优先使用传入参数，为null时从SessionInfo缓存的文本内容解析重建
            Request inviteRequest = originalInvite;
            if (inviteRequest == null && sessionInfo.getOriginalInviteContent() != null) {
                inviteRequest = SipAnalysisUtil.parseSipMessageRequest(sessionInfo.getOriginalInviteContent());
            }
            if (inviteRequest == null) {
                log.error("[handle407ProxyAuth][无法获取原始INVITE请求（参数和缓存均为空）] callId={}", callId);
                return false;
            }

            // 6. 计算Digest响应值（RFC 2617 Digest认证方案）
            // uri为INVITE的Request-URI字符串，需与Proxy-Authorization头中的uri参数一致
            URI requestUri = inviteRequest.getRequestURI();
            String uriString = requestUri.toString();
            String ha1 = DigestUtil.md5Hex(userName + ":" + realm + ":" + password);
            String ha2 = DigestUtil.md5Hex(Request.INVITE + ":" + uriString);
            String digestResponse = DigestUtil.md5Hex(ha1 + ":" + nonce + ":" + ha2);
            log.info("[handle407ProxyAuth][Digest计算完成] callId={}, userName={}, uri={}", callId, userName, uriString);

            // 7. 构造Proxy-Authorization头并注入到INVITE请求
            String authHeaderValue = String.format(
                    "Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"%s\", algorithm=%s",
                    userName, realm, nonce, uriString, digestResponse, algorithm);
            Header proxyAuthorizationHeader = headerFactory.createHeader("Proxy-Authorization", authHeaderValue);
            inviteRequest.removeHeader("Proxy-Authorization");
            inviteRequest.addHeader(proxyAuthorizationHeader);

            // 8. 更新事务标识：递增CSeq序号、生成新Via branch（RFC 3261要求新事务使用新branch）
            CSeqHeader cseqHeader = (CSeqHeader) inviteRequest.getHeader(CSeqHeader.NAME);
            if (cseqHeader != null) {
                cseqHeader.setSeqNumber(cseqHeader.getSeqNumber() + 1);
            }
            ViaHeader viaHeader = (ViaHeader) inviteRequest.getHeader(ViaHeader.NAME);
            if (viaHeader != null) {
                viaHeader.setBranch("z9hG4bK" + IdUtil.fastSimpleUUID());
            }

            // 9. 标记已重试并更新Redis缓存，确保跨请求/响应周期的循环防护有效
            sessionInfo.setAuthRetried(true);
            sessionManager.updateSessionInfo(sessionInfo);

            // 10. 按会话传输协议选择SipProvider重发INVITE
            String transport = sessionInfo.getToSipTransport() != null ? sessionInfo.getToSipTransport() : "udp";
            SipProvider targetProvider = "tcp".equalsIgnoreCase(transport) ? sipProviderTcp : sipProvider;
            targetProvider.sendRequest(inviteRequest);

            log.info("[handle407ProxyAuth][已注入Proxy-Authorization并重发INVITE] callId={}, gatewayId={}, userName={}",
                    callId, gatewayId, userName);
            return true;
        } catch (Exception e) {
            log.error("[handle407ProxyAuth][处理407鉴权失败] callId={}", callId, e);
            return false;
        }
    }

    /**
     * 识别SIP消息来源
     *
     * @param message SIP消息
     * @return 来源（WEBSOCKET、FREESWITCH、THIRD_PARTY）
     */
    public String identifyMessageSource(Message message) {
        // 获取 User-Agent 头部
        String userAgent = message.getHeader(UserAgentHeader.NAME) != null
                ? message.getHeader(UserAgentHeader.NAME).toString()
                : "";

        // 根据 User-Agent 识别响应来源
        if (userAgent.toUpperCase().contains(SipProxyConstants.FREESWITCH)) {
            return SipProxyConstants.FREESWITCH;
        } else if (userAgent.toUpperCase().contains(SipProxyConstants.JSSIP)) {
            return SipProxyConstants.WEBSOCKET;
        }

        // 根据 来源ip 判断是否在 freeswitch节点中 或者 第三方sip服务节点中 或者 分机数据域名中 都不是则返回空
        String sourceIp = SipAnalysisUtil.getSourceIpFromMessage(message);

        List<FsNodeInfo> allFreeSwitchNodes = nodeManager.getAllFreeSwitchNodes();
        List<String> fsIpList = allFreeSwitchNodes.stream().map(FsNodeInfo::getSipIp).toList();
        if (fsIpList.contains(sourceIp)) {
            return SipProxyConstants.FREESWITCH;
        }

        List<FsNodeInfo> allThirdPartyNodes = nodeManager.getAllThirdPartyNodes();
        List<String> tpIpList = allThirdPartyNodes.stream().map(FsNodeInfo::getSipIp).toList();
        if (tpIpList.contains(sourceIp)) {
            return SipProxyConstants.THIRD_PARTY;
        }
        
        return SipProxyConstants.WEBSOCKET;
    }
}
