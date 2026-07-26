package cn.ipcc.sipproxy.core.handler.request.ws;

import cn.hutool.core.text.StrPool;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.ipcc.sipproxy.api.authentication.SipAuthenticationProvider;
import cn.ipcc.sipproxy.api.trace.TraceContext;
import cn.ipcc.sipproxy.core.annotation.SipMethod;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.header.AuthorizationHeader;
import javax.sip.header.ContactHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * REGISTER请求处理器
 * 处理用户注册流程
 *
 * @author 芋道源码
 */
@Slf4j
@Component
@SipMethod(SipAnalysisUtil.REGISTER)
public class WsRegisterRequestHandler extends AbstractWsSipRequestHandler {

    /**
     * 链路追踪扩展点（可选注入，替代原 TraceUtil 静态调用）
     * <p>
     * 父程序未实现 TraceContext 时跳过，不影响业务流程。
     */
    @Autowired(required = false)
    private TraceContext traceContext;

    /**
     * SIP Digest 认证扩展点（可选注入，预留用于父程序覆盖本地 Digest 校验逻辑）
     * <p>
     * 当前实现保留原有 ha1/ha2 本地 Digest 计算，此扩展点供未来扩展使用，
     * 父程序可通过实现该接口替换认证逻辑（如对接外部鉴权系统）。
     */
    @Autowired(required = false)
    private SipAuthenticationProvider sipAuthenticationProvider;

    @Override
    protected void doHandle(String sessionId, Request request, String callId) throws Exception {
        // 入口设置 traceId,实现全链路日志串联;优先复用 SIP 请求的 Call-ID,缺失时回退到 UUID
        setTraceId(StrUtil.isNotBlank(callId) ? callId : IdUtil.fastSimpleUUID());
        try {
        log.info("[doHandle][开始处理REGISTER请求] sessionId={}", sessionId);

        AuthorizationHeader authorizationHeader = SipAnalysisUtil.getAuthorization(request);
        String fromDomain = SipAnalysisUtil.getFromDomain(request);

        if (authorizationHeader == null) {
            send401Response(sessionId, request, fromDomain);
            return;
        }

        String username = authorizationHeader.getUsername();
        String realm = authorizationHeader.getRealm();
        String nonce = authorizationHeader.getNonce();
        String uri = authorizationHeader.getURI().toString();
        String response = authorizationHeader.getResponse();

        // 通过 AgentInfoProvider 扩展点查询坐席信息（替代原 sysAgentService.listByNameAndDomainNoTenant）
        AgentInfo agent = agentInfoProvider.getAgent(username, realm);

        boolean isValid = validateAgent(agent, username, realm, nonce, uri, response, request.getMethod());

        if (isValid) {
            log.info("[doHandle][分机注册验证成功] username={}", username);
            send200OkResponse(sessionId, request);
            sessionManager.cacheRegisterInfo(sessionId, username, realm);
        } else {
            log.error("[doHandle][分机注册验证失败] username={}", username);
            send403Response(sessionId, request);
        }
        } finally {
            // 清理当前线程 traceId,避免线程复用导致日志串扰
            clearTraceId();
        }
    }

    /**
     * 校验坐席 Digest 凭证
     * <p>
     * 保留原有 Digest 认证逻辑（ha1/ha2 计算），仅将入参从 List<SysAgentDO> 改为 AgentInfo。
     *
     * @param agent    坐席信息（null 表示未查询到坐席）
     * @param username 用户名
     * @param realm    认证域
     * @param nonce    随机数
     * @param uri      URI
     * @param response 客户端计算的响应值
     * @param method   SIP 方法
     * @return 校验通过返回 true，失败返回 false
     */
    private boolean validateAgent(AgentInfo agent, String username, String realm,
                                  String nonce, String uri, String response, String method) {
        if (agent == null) {
            log.warn("[validateAgent][未查询到坐席信息] username={}, realm={}", username, realm);
            return false;
        }

        try {
            String ha1 = DigestUtil.md5Hex(username + StrPool.COLON + realm + StrPool.COLON + agent.getPassword());
            String ha2 = DigestUtil.md5Hex(method + StrPool.COLON + uri);
            String expectedResponse = DigestUtil.md5Hex(ha1 + StrPool.COLON + nonce + StrPool.COLON + ha2);
            return expectedResponse.equals(response);
        } catch (Exception e) {
            log.error("[validateAgent][密码验证异常] username={}", username, e);
            return false;
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
        messageForwarder.forwardToWebSocket(sessionId, response);
    }

    private void send200OkResponse(String sessionId, Request request) throws Exception {
        var response = messageFactory.createResponse(Response.OK, request);
        ContactHeader contactHeader = (ContactHeader) request.getHeader(ContactHeader.NAME);
        if (contactHeader != null) {
            response.addHeader(contactHeader);
        }
        response.addHeader(headerFactory.createHeader("Server", "CcSipProxyService/1.0"));
        response.addHeader(headerFactory.createHeader("Content-Length", "0"));
        messageForwarder.forwardToWebSocket(sessionId, response);
    }

    private void send403Response(String sessionId, Request request) throws Exception {
        var response = messageFactory.createResponse(Response.FORBIDDEN, request);
        response.addHeader(headerFactory.createHeader("Server", "CcSipProxyService/1.0"));
        response.addHeader(headerFactory.createHeader("Content-Length", "0"));
        messageForwarder.forwardToWebSocket(sessionId, response);
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
