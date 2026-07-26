package cn.ipcc.sipproxy.core.handler.request.ws;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.ipcc.sipproxy.api.trace.TraceContext;
import cn.ipcc.sipproxy.core.annotation.SipMethod;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyErrorCodeConstants;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.SipProxyException;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.address.SipURI;
import javax.sip.header.Header;
import javax.sip.message.Request;

/**
 * INVITE请求处理器
 * 处理内部呼叫和外呼流程
 *
 * @author 芋道源码
 */
@Slf4j
@Component
@SipMethod(SipAnalysisUtil.INVITE)
public class WsInviteRequestHandler extends AbstractWsSipRequestHandler {

    /**
     * 链路追踪扩展点（可选注入，替代原 TraceUtil 静态调用）
     */
    @Autowired(required = false)
    private TraceContext traceContext;

    /**
     * 处理WebSocket来源的INVITE请求
     *
     * 需求：坐席通过JsSIP发起INVITE呼叫（包括坐席间呼叫与外呼），SIP代理统一转发到FreeSWITCH park。
     *       对于通话中的re-INVITE（hold/unhold/Session Timer刷新），直接转发到已有的FS节点，不走park。
     * 预期结果：
     *   - 初始INVITE: 统一转发到选定的FreeSWITCH节点，由ESL处理器走号码路由匹配→IVR流程
     *   - re-INVITE: 直接转发到会话已有的FS节点，由FS处理hold/unhold媒体控制
     * 处理逻辑：
     * 1. 检查是否为re-INVITE（Call-ID已存在SessionInfo），若是则直接转发到已有FS节点
     * 2. 提取INVITE请求中的From/To信息
     * 3. 发送100 Trying临时响应
     * 4. 选择可用的FreeSwitch节点
     * 5. 号码分析驱动路由:不再根据被叫是否是注册用户区分内部呼叫/外呼,统一由ESL层走号码路由匹配
     * 6. 从请求头提取X-Gateway-Id（如果存在），作为后续IVR转接节点的网关覆盖项保存到会话信息
     * 7. 缓存会话信息并转发INVITE到FreeSwitch park
     */
    @Override
    protected void doHandle(String sessionId, Request request, String callId) throws Exception {
        // 入口设置 traceId,实现全链路日志串联;优先复用 SIP 请求的 Call-ID,缺失时回退到 UUID
        setTraceId(StrUtil.isNotBlank(callId) ? callId : IdUtil.fastSimpleUUID());
        try {
        log.info("[doHandle][INVITE] callId={}", callId);

        // re-INVITE检测:同一Call-ID已存在SessionInfo,说明是通话中的INVITE(hold/unhold/Session Timer刷新)
        // B2BUA架构下re-INVITE是独立事务,由sipproxy在两段对话间协调,直接转发到已有FS节点即可
        SessionInfo existingSession = sessionManager.getSessionInfo(callId);
        if (existingSession != null && existingSession.getFreeSwitchNode() != null) {
            log.info("[doHandle][检测到re-INVITE,直接转发到已有FS节点] callId={}, fs={}:{}",
                    callId, existingSession.getFreeSwitchNode().getSipIp(), existingSession.getFreeSwitchNode().getSipPort());
            messageForwarder.forwardToFreeSwitch(request, existingSession.getFreeSwitchNode());
            return;
        }

        String fromUser = SipAnalysisUtil.extractFromUser(request);
        String fromDomain = SipAnalysisUtil.extractFromDomain(request);
        String toUser = SipAnalysisUtil.extractToUser(request);
        String toDomain = SipAnalysisUtil.extractToDomain(request);

        sendTryingResponse(sessionId, request);
        log.info("[doHandle][已发送100 Trying响应] callId={}", callId);

        FsNodeInfo freeSwitchNode = nodeManager.selectFreeSwitchNode(callId);
        if (freeSwitchNode == null) {
            log.error("[handleInternalCall][没有可用的FreeSWITCH节点] callId={}", callId);
            throw new SipProxyException(SipProxyErrorCodeConstants.INTERNAL_SERVER_ERROR, "没有可用的FreeSWITCH节点");
        }

        log.info("[doHandle][已选择FreeSWITCH节点] callId={}, node={}:{}",
                callId, freeSwitchNode.getSipIp(), freeSwitchNode.getSipPort());

        SessionInfo sessionInfo = new SessionInfo(callId);
        sessionInfo.setSessionId(sessionId);
        sessionInfo.setFreeSwitchNode(freeSwitchNode);
        // 号码分析驱动路由:所有INVITE统一走FS park→号码路由匹配→IVR流程
        // 不再根据被叫是否是注册用户区分内部呼叫/外呼,callType统一标记为OUTBOUND(由ESL层走呼出号码路由匹配)
        sessionInfo.setCallType(SipProxyConstants.CALL_TYPE_OUTBOUND);
        SipURI contact = SipAnalysisUtil.extractContact(request);
        if (contact != null) {
            sessionInfo.setWebsocketContactName(contact.getUser());
            sessionInfo.setWebsocketContactIp(contact.getHost());
            sessionInfo.setWebsocketContactPort(contact.getPort());
            sessionInfo.setWebsocketContactTransport(contact.getTransportParam());
        }

        // 从INVITE请求头提取X-Gateway-Id,作为后续IVR转接节点的网关覆盖项(不再用于路由决策)
        String gatewayId = extractGatewayId(request);
        if (gatewayId != null) {
            sessionInfo.setGatewayId(gatewayId);
            log.info("[doHandle][提取到网关ID,作为IVR覆盖项] callId={}, gatewayId={}", callId, gatewayId);
        }

        sessionManager.cacheSessionInfo(sessionInfo);
        messageForwarder.forwardToFreeSwitch(request, freeSwitchNode);
        log.info("[doHandle][INVITE请求已转发到FreeSWITCH park] callId={}, fs={}:{}",
                callId, freeSwitchNode.getSipIp(), freeSwitchNode.getSipPort());

        } finally {
            // 清理当前线程 traceId,避免线程复用导致日志串扰
            clearTraceId();
        }
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

    /**
     * 从SIP请求头中提取X-Gateway-Id
     *
     * 需求：INVITE请求可能携带X-Gateway-Id自定义头域，用于标识呼叫来源网关
     * 预期结果：如果请求中存在X-Gateway-Id头域，返回其值；否则返回null
     * 处理逻辑：通过JAIN-SIP API获取X-Gateway-Id头域，提取其值并去除前后空白
     *
     * @param request SIP请求
     * @return 网关ID，不存在时返回null
     */
    private String extractGatewayId(Request request) {
        try {
            Header gatewayIdHeader = request.getHeader("X-Gateway-Id");
            if (gatewayIdHeader != null) {
                // Header.toString() 格式为 "X-Gateway-Id: value"，需要提取冒号后的值
                String headerStr = gatewayIdHeader.toString();
                int colonIndex = headerStr.indexOf(':');
                if (colonIndex >= 0 && colonIndex < headerStr.length() - 1) {
                    return headerStr.substring(colonIndex + 1).trim();
                }
            }
        } catch (Exception e) {
            log.warn("[extractGatewayId][提取X-Gateway-Id头域异常]", e);
        }
        return null;
    }

}
