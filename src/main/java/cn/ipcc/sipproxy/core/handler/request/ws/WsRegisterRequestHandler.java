package cn.ipcc.sipproxy.core.handler.request.ws;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.ipcc.sipproxy.api.authentication.AuthenticationCallback;
import cn.ipcc.sipproxy.api.authentication.SipAuthenticationProvider;
import cn.ipcc.sipproxy.api.trace.TraceContext;
import cn.ipcc.sipproxy.core.annotation.SipMethod;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.defaults.authentication.DefaultSipAuthenticationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.header.AuthorizationHeader;
import javax.sip.header.ContactHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * REGISTER 请求处理器
 * <p>
 * 设计意图：处理 JSSIP 客户端的 SIP REGISTER 注册流程，完成 Digest 认证并缓存注册信息。
 * <p>
 * 处理流程：
 * <ol>
 *   <li>首次 REGISTER 无 Authorization 头 → 返回 401 Unauthorized 挑战（携带 nonce）</li>
 *   <li>客户端携带 Authorization 头再次 REGISTER → 校验 Digest 凭证</li>
 *   <li>校验通过 → 返回 200 OK，缓存注册信息到会话管理器</li>
 *   <li>校验失败 → 返回 403 Forbidden</li>
 *   <li>认证完成后（无论成功/失败）触发 {@link AuthenticationCallback} 回调</li>
 * </ol>
 * <p>
 * 认证扩展点：
 * <ul>
 *   <li>默认实现 {@link DefaultSipAuthenticationProvider}：通过 AgentInfoProvider 获取坐席密码，
 *       sipproxy 内部完成 Digest HA1/HA2/response 计算与校验</li>
 *   <li>父程序实现 {@link SipAuthenticationProvider}：完全接管 Digest 认证，
 *       适用于已有完整认证体系的场景（如对接外部鉴权系统、RADIUS 服务器等）</li>
 * </ul>
 *
 * @author ipcc
 */
@Slf4j
@Component
@SipMethod(SipAnalysisUtil.REGISTER)
public class WsRegisterRequestHandler extends AbstractWsSipRequestHandler {

    /** 认证失败原因：Digest 校验失败 */
    private static final String REASON_DIGEST_FAILED = "Digest 校验失败";

    /**
     * 链路追踪扩展点（可选注入，替代原 TraceUtil 静态调用）
     * <p>
     * 父程序未实现 TraceContext 时跳过，不影响业务流程。
     */
    @Autowired(required = false)
    private TraceContext traceContext;

    /**
     * SIP Digest 认证扩展点
     * <p>
     * 默认实现 {@link DefaultSipAuthenticationProvider} 通过 AgentInfoProvider 获取密码后本地计算 Digest。
     * 父程序实现该接口可完全接管认证（如对接外部鉴权系统），覆盖默认实现。
     */
    @Autowired
    private SipAuthenticationProvider sipAuthenticationProvider;

    /**
     * 认证事件回调扩展点（可选注入）
     * <p>
     * 认证成功/失败后回调，便于父程序执行额外逻辑（更新坐席在线状态、
     * 触发签入流程、记录审计日志等）。未实现时使用 NoopAuthenticationCallback 兜底。
     */
    @Autowired(required = false)
    private AuthenticationCallback authenticationCallback;

    @Override
    protected void doHandle(String sessionId, Request request, String callId) throws Exception {
        // 入口设置 traceId,实现全链路日志串联;优先复用 SIP 请求的 Call-ID,缺失时回退到 UUID
        setTraceId(StrUtil.isNotBlank(callId) ? callId : IdUtil.fastSimpleUUID());
        try {
            log.info("[doHandle][开始处理REGISTER请求] sessionId={}", sessionId);

            AuthorizationHeader authorizationHeader = SipAnalysisUtil.getAuthorization(request);
            String fromDomain = SipAnalysisUtil.getFromDomain(request);

            // 首次请求无 Authorization 头，返回 401 挑战
            if (authorizationHeader == null) {
                send401Response(sessionId, request, fromDomain);
                return;
            }

            // 解析 Authorization 头字段
            String username = authorizationHeader.getUsername();
            String realm = authorizationHeader.getRealm();
            String nonce = authorizationHeader.getNonce();
            String uri = authorizationHeader.getURI().toString();
            String response = authorizationHeader.getResponse();
            String method = request.getMethod();

            // 按扩展点优先级校验凭证
            boolean isValid = validateCredentials(username, realm, nonce, uri, response, method);

            if (isValid) {
                log.info("[doHandle][分机注册验证成功] username={}", username);
                send200OkResponse(sessionId, request);
                sessionManager.cacheRegisterInfo(sessionId, username, realm);
                // 触发认证成功回调
                notifySuccess(username, realm, sessionId);
            } else {
                log.error("[doHandle][分机注册验证失败] username={}", username);
                send403Response(sessionId, request);
                // 触发认证失败回调
                notifyFailure(username, realm, REASON_DIGEST_FAILED);
            }
        } finally {
            // 清理当前线程 traceId,避免线程复用导致日志串扰
            clearTraceId();
        }
    }

    /**
     * 校验 Digest 凭证
     * <p>
     * 处理逻辑：统一委托 {@link SipAuthenticationProvider} 扩展点校验。
     * 默认实现 {@link DefaultSipAuthenticationProvider} 通过 AgentInfoProvider 获取密码后本地计算 Digest；
     * 父程序可实现接口覆盖默认行为（如对接外部鉴权系统）。
     *
     * @param extension 分机号
     * @param domain    认证域
     * @param nonce     401 响应下发的 nonce
     * @param uri       Authorization 头的 URI
     * @param response  客户端计算的 Digest response
     * @param method    SIP 方法
     * @return 校验通过返回 true，失败返回 false
     */
    private boolean validateCredentials(String extension, String domain, String nonce,
                                         String uri, String response, String method) {
        return sipAuthenticationProvider.authenticate(extension, domain, nonce, uri, response, method);
    }

    /**
     * 触发认证成功回调
     * <p>
     * 委托 AuthenticationCallback 扩展点，父程序未实现时由 NoopAuthenticationCallback 记录日志。
     *
     * @param extension 分机号
     * @param domain    域名
     * @param sessionId WebSocket 会话 ID
     */
    private void notifySuccess(String extension, String domain, String sessionId) {
        if (authenticationCallback != null) {
            authenticationCallback.onSuccess(extension, domain, sessionId);
        }
    }

    /**
     * 触发认证失败回调
     * <p>
     * 委托 AuthenticationCallback 扩展点，父程序未实现时由 NoopAuthenticationCallback 记录 WARN 日志。
     *
     * @param extension 分机号（可能为 null）
     * @param domain    域名（可能为 null）
     * @param reason    失败原因
     */
    private void notifyFailure(String extension, String domain, String reason) {
        if (authenticationCallback != null) {
            authenticationCallback.onFailure(extension, domain, reason);
        }
    }

    private void send401Response(String sessionId, Request request, String realm) throws Exception {
        var response = messageFactory.createResponse(Response.UNAUTHORIZED, request);
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        String authenticateValue = String.format("Digest realm=\"%s\", nonce=\"%s\"", realm, nonce);
        var wwwAuthenticateHeader = headerFactory.createHeader("WWW-Authenticate", authenticateValue);
        response.addHeader(wwwAuthenticateHeader);
        response.addHeader(headerFactory.createHeader("Server", "CcSipProxyService/1.0"));
        response.addHeader(headerFactory.createHeader("Content-Length", "0"));
        log.warn("[send401Response][401 调试] realm={}, nonce={}, 完整响应={}", realm, nonce, response);
        messageForwarder.toWebSocket(sessionId, response);
    }

    private void send200OkResponse(String sessionId, Request request) throws Exception {
        var response = messageFactory.createResponse(Response.OK, request);
        ContactHeader contactHeader = (ContactHeader) request.getHeader(ContactHeader.NAME);
        if (contactHeader != null) {
            response.addHeader(contactHeader);
        }
        response.addHeader(headerFactory.createHeader("Server", "CcSipProxyService/1.0"));
        response.addHeader(headerFactory.createHeader("Content-Length", "0"));
        messageForwarder.toWebSocket(sessionId, response);
    }

    private void send403Response(String sessionId, Request request) throws Exception {
        var response = messageFactory.createResponse(Response.FORBIDDEN, request);
        response.addHeader(headerFactory.createHeader("Server", "CcSipProxyService/1.0"));
        response.addHeader(headerFactory.createHeader("Content-Length", "0"));
        messageForwarder.toWebSocket(sessionId, response);
    }

    /**
     * 设置 traceId（替代原 TraceUtil.setTraceId 静态调用）
     * <p>
     * 父程序未实现 TraceContext 时跳过，不影响业务流程。
     *
     * @param traceId 链路追踪 ID（通常为 SIP Call-ID）
     */
    private void setTraceId(String traceId) {
        if (traceContext != null) {
            traceContext.setTraceId(traceId);
        }
    }

    /**
     * 清理当前线程 traceId（替代原 TraceUtil.clear()）
     * <p>
     * TraceContext 接口未定义 clear 方法，复用 setTraceId(null) 语义实现清理。
     * 父程序未实现 TraceContext 时跳过。
     */
    private void clearTraceId() {
        if (traceContext != null) {
            traceContext.setTraceId(null);
        }
    }
}
