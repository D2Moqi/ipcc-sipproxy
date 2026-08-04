package cn.ipcc.sipproxy.core;

import cn.ipcc.sipproxy.autoconfigure.SipProxyProperties;
import cn.ipcc.sipproxy.api.gateway.MessageSourceIdentifier;
import cn.ipcc.sipproxy.api.security.IpWhitelist;
import cn.ipcc.sipproxy.api.security.SipRateLimiter;
import cn.ipcc.sipproxy.core.auth.GatewayAuthManager;
import cn.ipcc.sipproxy.core.forwarder.SipMessageForwarder;
import cn.ipcc.sipproxy.core.handler.request.sip.AbstractSipRequestHandler;
import cn.ipcc.sipproxy.core.handler.request.sip.SipRequestHandlerFactory;
import cn.ipcc.sipproxy.core.handler.request.ws.AbstractWsSipRequestHandler;
import cn.ipcc.sipproxy.core.handler.request.ws.WsSipRequestHandlerFactory;
import cn.ipcc.sipproxy.core.handler.response.AbstractSipResponseHandler;
import cn.ipcc.sipproxy.core.handler.response.SipResponseHandlerFactory;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.session.SipSessionManager;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sip.*;
import javax.sip.address.SipURI;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.UserAgentHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.util.Properties;

/**
 * SIP 代理主服务
 * <p>
 * 设计意图：
 * 作为 sipproxy 模块的核心 SipListener 实现,承担以下职责：
 * <ol>
 *   <li>初始化 JAIN-SIP 协议栈（UDP/TCP 双栈监听）</li>
 *   <li>处理来自 WebSocket 的 SIP 消息（INVITE/BYE/REGISTER/OPTIONS/REFER 等）</li>
 *   <li>处理来自 FreeSWITCH 或第三方 SIP 服务的 SIP 请求/响应</li>
 *   <li>管理 SIP 事务生命周期（Timeout/IOException/TransactionTerminated 等事件）</li>
 *   <li>WebSocket 连接关闭时清理对应的注册信息</li>
 * </ol>
 * <p>
 * 不依赖任何 yudao 框架,配置通过 {@link SipProxyProperties} 注入。
 *
 * @author ipcc
 */
@Slf4j
@Service
public class SipProxyService implements SipListener {

    @Resource
    private SipSessionManager sessionManager;

    @Resource
    private SipMessageForwarder messageForwarder;

    @Resource
    private WsSipRequestHandlerFactory handlerFactory;

    @Resource
    private SipRequestHandlerFactory sipRequestHandlerFactory;

    @Resource
    private SipResponseHandlerFactory responseHandlerFactory;

    @Resource
    private SipProxyProperties sipProxyProperties;

    @Resource
    private GatewayAuthManager gatewayAuthManager;

    @Resource
    private MessageSourceIdentifier messageSourceIdentifier;

    @Resource
    private IpWhitelist ipWhitelist;

    @Resource
    private SipRateLimiter sipRateLimiter;

    /** JAIN-SIP 协议栈实例 */
    private SipStack sipStack;
    /** UDP SipProvider（用于 SIP UDP 收发） */
    private SipProvider sipProvider;
    /** TCP SipProvider（用于 SIP TCP 收发，主要兼容 TCP SIP 终端） */
    private SipProvider sipProviderTcp;
    /** SIP 消息工厂（构造 Request/Response） */
    private MessageFactory messageFactory;
    /** SIP 头部工厂（构造 Via/Contact 等 header） */
    private HeaderFactory headerFactory;
    /** SIP 地址工厂（构造 SipURI/Address 等） */
    private javax.sip.address.AddressFactory addressFactory;

    /**
     * 初始化 SIP 协议栈与处理器
     * <p>
     * 触发时机：Spring 容器启动后由 {@link PostConstruct} 调用。
     * 处理流程：
     * <ol>
     *   <li>创建 SipStack、MessageFactory、HeaderFactory</li>
     *   <li>创建 UDP/TCP 监听点并注册 SipListener</li>
     *   <li>初始化请求/响应处理器工厂（注入工厂实例）</li>
     * </ol>
     */
    @PostConstruct
    public void init() {
        initializeSipStack();
        initializeHandlers();
    }

    /**
     * 初始化 JAIN-SIP 协议栈
     * <p>
     * 设计说明：
     * <ul>
     *   <li>STACK_NAME 固定为 "SipServiceStack"，仅供 JAIN-SIP 内部标识</li>
     *   <li>AUTOMATIC_DIALOG_SUPPORT=off，禁用自动对话框管理（B2BUA 场景需手动管理对话）</li>
     *   <li>同时监听 UDP 和 TCP，端口取自 {@link SipProxyProperties.Sip#getPort()}</li>
     * </ul>
     */
    private void initializeSipStack() {
        try {
            SipFactory sipFactory = SipFactory.getInstance();
            sipFactory.setPathName(SipProxyConstants.SIP_STACK_PATH);

            Properties properties = new Properties();
            properties.setProperty("javax.sip.STACK_NAME", SipProxyConstants.STACK_NAME);
            properties.setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", SipProxyConstants.AUTOMATIC_DIALOG_SUPPORT_OFF);

            sipStack = sipFactory.createSipStack(properties);

            messageFactory = sipFactory.createMessageFactory();
            headerFactory = sipFactory.createHeaderFactory();
            addressFactory = sipFactory.createAddressFactory();

            int sipPort = sipProxyProperties.getSip().getPort();
            String bindAddress = sipProxyProperties.getSip().getBindAddress();

            // UDP 监听点：用于接收/发送 SIP 消息
            ListeningPoint udpListeningPoint = sipStack.createListeningPoint(bindAddress, sipPort, SipProxyConstants.TRANSPORT_UDP);
            sipProvider = sipStack.createSipProvider(udpListeningPoint);
            sipProvider.addSipListener(this);

            // TCP 监听点：主要用来兼容接收 TCP SIP 消息
            ListeningPoint tcpListeningPoint = sipStack.createListeningPoint(bindAddress, sipPort, SipProxyConstants.TRANSPORT_TCP);
            sipProviderTcp = sipStack.createSipProvider(tcpListeningPoint);
            sipProviderTcp.addSipListener(this);

            log.info("[initializeSipStack][SIP 服务启动成功] port={}, bindAddress={}", sipPort, bindAddress);
        } catch (Exception e) {
            log.error("[initializeSipStack][启动 SIP 服务失败]", e);
            throw new RuntimeException("启动 SIP 服务失败", e);
        }
    }

    /**
     * 初始化处理器工厂
     * <p>
     * 将 SIP 栈创建的 MessageFactory/HeaderFactory 注入到处理器工厂、消息转发器和认证管理器，
     * 供后续 SIP 消息构造、转发与认证使用。
     */
    private void initializeHandlers() {
        handlerFactory.setHeaderFactory(headerFactory);
        handlerFactory.setMessageFactory(messageFactory);
        sipRequestHandlerFactory.setHeaderFactory(headerFactory);

        handlerFactory.init();
        sipRequestHandlerFactory.init();

        messageForwarder.setSipProvider(sipProvider);
        messageForwarder.setSipProviderTcp(sipProviderTcp);
        messageForwarder.setHeaderFactory(headerFactory);
        messageForwarder.setAddressFactory(addressFactory);
        messageForwarder.setLocalIpAddress(sipProxyProperties.getSip().getPublicIp());
        messageForwarder.setSipPort(sipProxyProperties.getSip().getPublicPort());

        gatewayAuthManager.init(headerFactory, addressFactory, sipProvider, sipProviderTcp);
    }

    /**
     * 容器销毁时停止 SIP 服务
     */
    @PreDestroy
    public void destroy() {
        stopSipService();
    }

    /**
     * 停止 SIP 服务
     * <p>
     * 处理流程：移除 SipListener → 停止 SipStack，确保端口释放。
     */
    private void stopSipService() {
        try {
            if (sipProvider != null) {
                sipProvider.removeSipListener(this);
            }
            if (sipStack != null) {
                sipStack.stop();
            }
            log.info("[stopSipService][SIP 服务已停止]");
        } catch (Exception e) {
            log.error("[stopSipService][停止 SIP 服务失败]", e);
        }
    }

    /**
     * 清理 TCP 请求的 Via 头
     * <p>
     * 业务背景：JAIN-SIP 栈会根据 RFC 3581 自动在 Via 头添加 received/rport 参数用于 NAT 穿透，
     * 但在 B2BUA 代理场景下这些参数会导致消息路由问题，需要清理为干净的 Via 头。
     * <p>
     * 处理逻辑：仅对 TCP 请求处理（transport=tcp），保留 host/port/branch 三个核心参数。
     *
     * @param request SIP 请求
     */
    private void cleanViaHeaderForTcpRequest(Request request) {
        try {
            ViaHeader viaHeader = (ViaHeader) request.getHeader(ViaHeader.NAME);
            if (viaHeader == null) {
                return;
            }
            String transport = viaHeader.getTransport();
            if (!SipProxyConstants.TRANSPORT_TCP.equalsIgnoreCase(transport)) {
                return;
            }
            String host = viaHeader.getHost();
            int port = viaHeader.getPort();
            String branch = viaHeader.getBranch();

            StringBuilder cleanViaBuilder = new StringBuilder();
            cleanViaBuilder.append("SIP/2.0/").append(transport.toUpperCase())
                    .append(" ").append(host);
            if (port > 0) {
                cleanViaBuilder.append(":").append(port);
            }
            cleanViaBuilder.append(";branch=").append(branch);
            String cleanVia = cleanViaBuilder.toString();

            request.removeHeader(ViaHeader.NAME);
            ViaHeader newViaHeader = (ViaHeader) headerFactory.createHeader(ViaHeader.NAME, cleanVia);
            request.addHeader(newViaHeader);

            log.info("[cleanViaHeaderForTcpRequest][已清理 TCP 请求的 Via 头] method={}, cleanVia={}",
                    request.getMethod(), cleanVia);
        } catch (Exception e) {
            log.error("[cleanViaHeaderForTcpRequest][清理 Via 头失败]", e);
        }
    }

    /**
     * 清理 WebSocket 会话关联的注册信息
     * <p>
     * 触发场景：
     * <ul>
     *   <li>{@code SipWebSocketHandler.afterConnectionClosed}：WebSocket 连接正常关闭</li>
     *   <li>{@code ZombieSessionCleaner.cleanZombieSessions}：清理僵尸会话</li>
     * </ul>
     * 处理逻辑：委托 {@link SipSessionManager#cleanupRegisterInfo(String)} 删除 Redis 中的
     * SESSION_REGISTER_MAPPING_PREFIX 和 USER_SESSION_MAPPING_PREFIX 两类 Key。
     *
     * @param sessionId WebSocket 会话 ID
     */
    public void cleanupRegisterInfo(String sessionId) {
        sessionManager.cleanupRegisterInfo(sessionId);
    }

    /**
     * 处理来自 WebSocket 的 SIP 消息
     * <p>
     * 触发场景：{@code SipWebSocketHandler} 通过 {@code SipFrameReassembler} 重组出完整 SIP 消息后调用。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>解析 SIP 消息文本为 JAIN-SIP Message 对象</li>
     *   <li>提取 Call-ID,按 Call-ID 查询 SessionInfo</li>
     *   <li>若存在会话,更新会话中的 WebSocket 端 Contact 联系地址（用于响应回送）</li>
     *   <li>Request 类型:按方法分发到对应 WsXxxRequestHandler</li>
     *   <li>Response 类型:注入 User-Agent 头后分发到 SipResponseHandlerFactory</li>
     * </ol>
     *
     * @param sessionId     WebSocket 会话 ID
     * @param sipMessageStr SIP 消息字符串
     * @throws Exception 处理异常
     */
    public void handleWebSocketSipMessage(String sessionId, String sipMessageStr) throws Exception {
        log.debug("[handleWebSocketSipMessage][接收 WebSocket SIP 消息] sessionId={}", sessionId);

        Message sipMessage = SipAnalysisUtil.parseSipMessage(sipMessageStr);
        String callId = SipAnalysisUtil.getCallId(sipMessage);
        // 更新通话信息中 ws 端的联系地址（ws 第一次发送 invite 时 sessionInfo 是空的,会在 invite 事件处理中单独设置 ws 端联系地址）
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo != null) {
            SipURI contact = SipAnalysisUtil.extractContact(sipMessage);
            if (contact != null) {
                sessionInfo.setWebsocketContactName(contact.getUser());
                sessionInfo.setWebsocketContactIp(contact.getHost());
                sessionInfo.setWebsocketContactPort(contact.getPort());
                sessionInfo.setWebsocketContactTransport(contact.getTransportParam());
                sessionManager.updateSessionInfo(sessionInfo);
                log.info("[handleWebSocketSipMessage][更新通话信息中 ws 端的联系地址] sessionId={}, contact={}", sessionId, contact);
            }
        }
        Header userAgentHeader = headerFactory.createHeader(UserAgentHeader.NAME, SipProxyConstants.IPCC_JSSIP);
        sipMessage.addHeader(userAgentHeader);
        if (sipMessage instanceof Request request) {
            String method = request.getMethod();
            AbstractWsSipRequestHandler handler = handlerFactory.getHandler(method);
            if (handler != null) {
                handler.handle(sessionId, request);
            } else {
                log.warn("[handleWebSocketSipMessage][未处理的 SIP 方法,使用默认处理器] method={}", method);
                handlerFactory.getDefaultHandler().handle(sessionId, request);
            }
        } else if (sipMessage instanceof Response response) {
            log.info("[handleSipResponse][收到 ws-SIP 响应] statusCode={}, callId={}, response={}",
                    response.getStatusCode(), SipAnalysisUtil.getCallId(response), response);
            AbstractSipResponseHandler handler = responseHandlerFactory.getHandler(response);
            handler.handle(response);
        }
    }

    /**
     * JAIN-SIP 请求回调
     * <p>
     * 触发场景：SIP 协议栈收到来自 FreeSWITCH 或第三方 SIP 服务的请求时回调。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>清理 TCP 请求的 Via 头（仅 TCP 生效）</li>
     *   <li>委托 {@link MessageSourceIdentifier} 扩展点识别消息来源（WEBSOCKET/FREESWITCH/THIRD_PARTY）</li>
     *   <li>委托 {@link SipRateLimiter} 扩展点校验请求速率（不通过返回 429 Too Many Requests）</li>
     *   <li>THIRD_PARTY 来源时委托 {@link IpWhitelist} 扩展点校验来源 IP（不通过返回 403 Forbidden）</li>
     *   <li>按 SIP 方法分发到对应 SipXxxRequestHandler</li>
     * </ol>
     */
    @Override
    public void processRequest(RequestEvent requestEvent) {
        try {
            Request request = requestEvent.getRequest();
            cleanViaHeaderForTcpRequest(request);

            String method = request.getMethod();
            String callId = SipAnalysisUtil.getCallId(request);
            log.info("[processRequest][收到 SIP 请求] method={}, callId={}, request={}", method, callId, request);

            // 委托 MessageSourceIdentifier 扩展点识别消息来源
            String source = messageSourceIdentifier.identifySource(request);
            log.info("[processRequest][消息来源] source={}, method={}, callId={}", source, method, callId);
            if (source == null) {
                log.warn("[processRequest][未知的消息来源] source={}", source);
                return;
            }

            // 委托 SipRateLimiter 扩展点校验请求速率
            String sourceIp = SipAnalysisUtil.getSourceIpFromMessage(request);
            if (sourceIp != null && !sipRateLimiter.tryAcquire(sourceIp, method)) {
                log.warn("[processRequest][请求被限流] sourceIp={}, method={}, callId={}", sourceIp, method, callId);
                sendErrorResponse(requestEvent, SipProxyConstants.STATUS_TOO_MANY_REQUESTS);
                return;
            }

            // THIRD_PARTY 来源时委托 IpWhitelist 扩展点校验来源 IP
            if (SipProxyConstants.THIRD_PARTY.equals(source) && sourceIp != null && !ipWhitelist.isAllowed(sourceIp)) {
                log.warn("[processRequest][来源IP不在白名单] sourceIp={}, callId={}", sourceIp, callId);
                sendErrorResponse(requestEvent, Response.FORBIDDEN);
                return;
            }

            AbstractSipRequestHandler handler = sipRequestHandlerFactory.getHandler(method);
            if (handler != null) {
                handler.handle(request, callId, source);
            } else {
                log.warn("[processRequest][未处理的 SIP 方法,使用默认处理器] method={}", method);
                sipRequestHandlerFactory.getDefaultHandler().handle(request, callId, source);
            }
        } catch (Exception e) {
            log.error("[processRequest][处理 SIP 请求失败]", e);
        }
    }

    /**
     * 发送 SIP 错误响应
     * <p>
     * 处理逻辑：创建指定状态码的 Response，通过 ServerTransaction 发送给请求方。
     * 用于安全校验失败（403/429）等场景。
     *
     * @param requestEvent 原始请求事件
     * @param statusCode   SIP 状态码（如 403 Forbidden / 429 Too Many Requests）
     */
    private void sendErrorResponse(RequestEvent requestEvent, int statusCode) {
        try {
            Request request = requestEvent.getRequest();
            Response response = messageFactory.createResponse(statusCode, request);
            ServerTransaction serverTransaction = requestEvent.getServerTransaction();
            if (serverTransaction == null) {
                SipProvider sipProvider = (SipProvider) requestEvent.getSource();
                serverTransaction = sipProvider.getNewServerTransaction(request);
            }
            serverTransaction.sendResponse(response);
            log.info("[sendErrorResponse][已发送错误响应] statusCode={}, callId={}",
                    statusCode, SipAnalysisUtil.getCallId(request));
        } catch (Exception e) {
            log.error("[sendErrorResponse][发送错误响应失败] statusCode={}", statusCode, e);
        }
    }

    /**
     * JAIN-SIP 响应回调
     * <p>
     * 触发场景：SIP 协议栈收到来自 FreeSWITCH 或第三方 SIP 服务的响应时回调。
     * 处理逻辑：委托给 SipResponseHandlerFactory 选择对应处理器并处理。
     */
    @Override
    public void processResponse(ResponseEvent responseEvent) {
        try {
            Response response = responseEvent.getResponse();
            String statusCode = String.valueOf(response.getStatusCode());
            String callId = SipAnalysisUtil.getCallId(response);
            log.info("[processResponse][收到 SIP 响应] statusCode={}, callId={}, response={}", statusCode, callId, response);
            AbstractSipResponseHandler handler = responseHandlerFactory.getHandler(response);
            handler.handle(response);
        } catch (Exception e) {
            log.error("[processResponse][处理 SIP 响应失败]", e);
        }
    }

    /**
     * JAIN-SIP 事务超时回调
     * <p>
     * 场景：事务层重传定时器到期未收到响应。
     * 处理：仅记录 warn 日志,不影响其他事务。
     */
    @Override
    public void processTimeout(TimeoutEvent timeoutEvent) {
        String method = timeoutEvent.getClientTransaction() != null ?
                timeoutEvent.getClientTransaction().getRequest().getMethod() :
                (timeoutEvent.getServerTransaction() != null ?
                        timeoutEvent.getServerTransaction().getRequest().getMethod() : SipProxyConstants.UNKNOWN_METHOD);
        log.warn("[processTimeout][SIP 消息超时] method={}, 场景: 事务层重传定时器到期未收到响应", method);
    }

    /**
     * JAIN-SIP IO 异常回调
     * <p>
     * 场景：网络连接断开或节点不可达。
     * 处理：仅记录 error 日志,由调用方决定是否重试。
     */
    @Override
    public void processIOException(IOExceptionEvent exceptionEvent) {
        log.error("[processIOException][SIP 通信发生 IO 异常] host={}, port={}, transport={}, 场景: 网络连接断开或节点不可达",
                exceptionEvent.getHost(), exceptionEvent.getPort(), exceptionEvent.getTransport());
    }

    /**
     * JAIN-SIP 事务终止回调
     * <p>
     * 场景：事务正常完成/超时终止/收到错误响应。
     */
    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent transactionTerminatedEvent) {
        Transaction transaction = transactionTerminatedEvent.getClientTransaction() != null ?
                transactionTerminatedEvent.getClientTransaction() :
                transactionTerminatedEvent.getServerTransaction();
        if (transaction != null) {
            log.debug("[processTransactionTerminated][SIP 事务终止] method={}, state={}, 场景: 事务正常完成/超时终止/收到错误响应",
                    transaction.getRequest().getMethod(), transaction.getState());
        } else {
            log.debug("[processTransactionTerminated][SIP 事务终止]");
        }
    }

    /**
     * JAIN-SIP 对话终止回调
     * <p>
     * 场景：BYE 结束通话/对话超时/呼叫取消。
     */
    @Override
    public void processDialogTerminated(DialogTerminatedEvent dialogTerminatedEvent) {
        Dialog dialog = dialogTerminatedEvent.getDialog();
        if (dialog != null) {
            log.debug("[processDialogTerminated][SIP 对话终止] callId={}, state={}, 场景: BYE 结束通话/对话超时/呼叫取消",
                    dialog.getCallId().getCallId(), dialog.getState());
        } else {
            log.debug("[processDialogTerminated][SIP 对话终止]");
        }
    }
}
