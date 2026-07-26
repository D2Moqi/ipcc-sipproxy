package cn.ipcc.sipproxy.core.handler.request.sip;

import cn.ipcc.sipproxy.core.handler.response.ResponseForwardingStrategy;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * 传入其他SIP请求处理器
 * 处理来自FreeSWITCH/第三方SIP的其他SIP请求（非INVITE、BYE、ACK）
 *
 * @author 芋道源码
 */
@Slf4j
@Component
public class SipDefaultRequestHandler extends AbstractSipRequestHandler {

    @Resource
    private ResponseForwardingStrategy responseForwardingStrategy;

    /**
     * 处理传入的会话内SIP请求（PRACK/UPDATE/INFO等非INVITE/BYE/ACK方法）
     *
     * 需求背景:
     *   会话内SIP方法（PRACK/UPDATE/INFO等）属于会话建立后的后续信令，需按会话状态决策转发目标，
     *   而非按To头查注册用户转发。原逻辑按To头查注册无法正确处理FS↔第三方网关的会话内方法
     *   （例如第三方网关发送的UPDATE到达时，To头指向被叫分机，但实际应转发回FS）。
     *
     * 处理逻辑:
     *   1. 提取To头信息并校验（用于fallback路径与错误响应）
     *   2. 按 Call-ID 查 SessionInfo
     *   3. SessionInfo 存在: 复用 ResponseForwardingStrategy 按 source + callType 决策转发目标，
     *      按 target 分支调用 messageForwarder 转发：
     *        - WEBSOCKET   → modifyWsProxyHeaders + forwardToWebSocket(sessionId)
     *        - FREESWITCH  → forwardToFreeSwitch(freeSwitchNode)
     *        - THIRD_PARTY → forwardToThirdParty(thirdPartyNode)
     *   4. SessionInfo 不存在: fallback 到 forwardRequestByRegistration，保证未走 sipproxy 的会话向后兼容
     *
     * 异常场景:
     *   - To头缺失: 返回 BAD_REQUEST
     *   - SessionInfo 不存在: fallback 到按注册转发
     *   - 决策目标对应节点缺失（如 freeSwitchNode 为 null）: 记录告警日志
     *
     * 边界条件:
     *   - Call-ID 入参为空: 直接 fallback 到按注册转发
     *   - 未知 source/callType: ResponseForwardingStrategy 默认返回 FREESWITCH
     *
     * @param request SIP请求
     * @param callId  Call-ID，会话标识
     * @param source  消息来源（WEBSOCKET/FREESWITCH/THIRD_PARTY），用于决策转发目标
     * @throws Exception 转发失败或构造错误响应失败时抛出
     */
    @Override
    public void handle(Request request, String callId, String source) throws Exception {
        log.info("[handleIncomingRequest][处理传入其他SIP请求] method={}, callId={}, source={}",
                request.getMethod(), callId, source);

        // Call-ID 缺失时无法按会话决策，直接 fallback 到按注册转发
        if (callId == null || callId.isEmpty()) {
            log.warn("[handleIncomingRequest][Call-ID为空，fallback到按注册转发]");
            fallbackForwardByRegistration(request, callId);
            return;
        }

        // 提取 To 头信息，用于 fallback 路径与错误响应
        String toUser = SipAnalysisUtil.extractToUser(request);
        String toDomain = SipAnalysisUtil.extractToDomain(request);
        if (!validateToHeader(toUser, toDomain)) {
            sendErrorResponse(callId, request, Response.BAD_REQUEST);
            return;
        }

        // 按 Call-ID 查会话信息
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo == null) {
            // SessionInfo 不存在，说明该会话未经过 sipproxy 完整建立（如未走 INVITE 处理器），
            // fallback 到按 To 头查注册转发，保证向后兼容
            log.warn("[handleIncomingRequest][SessionInfo不存在，fallback到按注册转发] callId={}", callId);
            forwardRequestByRegistration(request, callId, toUser, toDomain);
            return;
        }

        // 会话已建立，刷新会话信息（保持会话活跃，避免被淘汰清理）
        sessionManager.updateSessionInfo(sessionInfo);
        log.info("[handleIncomingRequest][会话已建立，按source+callType决策转发] callId={}, source={}, callType={}",
                callId, source, sessionInfo.getCallType());

        // 复用 ResponseForwardingStrategy 按 source + callType 决策转发目标
        String target = responseForwardingStrategy.getForwardingTarget(source, sessionInfo.getCallType());
        log.info("[handleIncomingRequest][决策转发目标] callId={}, source={}, callType={}, target={}",
                callId, source, sessionInfo.getCallType(), target);

        // 按 target 分支转发
        forwardByTarget(request, callId, target, sessionInfo);
    }

    /**
     * 按决策目标分支转发会话内SIP请求
     *
     * 设计思路:
     *   统一在 switch 分支中处理三种转发目标，失败时记录告警日志并向上抛出，
     *   由上层事务处理器决定是否回送错误响应；未知目标 fallback 到按注册转发兜底。
     *
     * @param request     SIP请求
     * @param callId      Call-ID
     * @param target      决策目标（WEBSOCKET/FREESWITCH/THIRD_PARTY）
     * @param sessionInfo 会话信息（提供 sessionId/freeSwitchNode/thirdPartyNode）
     * @throws Exception 转发失败时抛出
     */
    private void forwardByTarget(Request request, String callId, String target, SessionInfo sessionInfo) throws Exception {
        try {
            switch (target) {
                case SipProxyConstants.WEBSOCKET:
                    forwardToWebSocket(request, sessionInfo);
                    break;
                case SipProxyConstants.FREESWITCH:
                    forwardToFreeSwitch(request, sessionInfo);
                    break;
                case SipProxyConstants.THIRD_PARTY:
                    forwardToThirdParty(request, sessionInfo);
                    break;
                default:
                    // 未知目标，记录告警并 fallback 到按注册转发兜底
                    log.warn("[forwardByTarget][未知转发目标，fallback到按注册转发] target={}, callId={}", target, callId);
                    String toUser = SipAnalysisUtil.extractToUser(request);
                    String toDomain = SipAnalysisUtil.extractToDomain(request);
                    forwardRequestByRegistration(request, callId, toUser, toDomain);
            }
        } catch (Exception e) {
            log.error("[forwardByTarget][转发失败] target={}, callId={}", target, callId, e);
            throw e;
        }
    }

    /**
     * 转发会话内SIP请求到WebSocket（坐席端）
     *
     * 处理逻辑:
     *   1. 校验 sessionId 是否存在
     *   2. 调用 modifyWsProxyHeaders 改写 Contact/Via/Request-URI 为 WebSocket 代理地址
     *   3. 调用 forwardToWebSocket 按 sessionId 发送
     *
     * @param request     SIP请求
     * @param sessionInfo 会话信息
     * @throws Exception 转发失败时抛出
     */
    private void forwardToWebSocket(Request request, SessionInfo sessionInfo) throws Exception {
        String sessionId = sessionInfo.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("[forwardToWebSocket][WebSocket会话ID不存在，跳过转发] callId={}", sessionInfo.getCallId());
            return;
        }
        // 改写 WebSocket 代理头（Contact/Via/Request-URI）
        Message modifiedRequest = messageForwarder.modifyWsProxyHeaders(request);
        messageForwarder.forwardToWebSocket(sessionId, modifiedRequest);
        log.info("[forwardToWebSocket][已转发到WebSocket] callId={}, sessionId={}",
                sessionInfo.getCallId(), sessionId);
    }

    /**
     * 转发会话内SIP请求到FreeSWITCH
     *
     * 处理逻辑:
     *   1. 校验 freeSwitchNode 是否存在
     *   2. 调用 forwardToFreeSwitch 转发（内部含故障转移和头域改写）
     *
     * @param request     SIP请求
     * @param sessionInfo 会话信息
     * @throws Exception 转发失败时抛出
     */
    private void forwardToFreeSwitch(Request request, SessionInfo sessionInfo) throws Exception {
        if (sessionInfo.getFreeSwitchNode() == null) {
            log.warn("[forwardToFreeSwitch][FreeSWITCH节点不存在，跳过转发] callId={}", sessionInfo.getCallId());
            return;
        }
        messageForwarder.forwardToFreeSwitch(request, sessionInfo.getFreeSwitchNode());
        log.info("[forwardToFreeSwitch][已转发到FreeSWITCH] callId={}, node={}:{}",
                sessionInfo.getCallId(),
                sessionInfo.getFreeSwitchNode().getSipIp(),
                sessionInfo.getFreeSwitchNode().getSipPort());
    }

    /**
     * 转发会话内SIP请求到第三方SIP服务
     *
     * 处理逻辑:
     *   1. 校验 thirdPartyNode 是否存在
     *   2. 调用 forwardToThirdParty 转发（内部含头域改写）
     *
     * @param request     SIP请求
     * @param sessionInfo 会话信息
     * @throws Exception 转发失败时抛出
     */
    private void forwardToThirdParty(Request request, SessionInfo sessionInfo) throws Exception {
        if (sessionInfo.getThirdPartyNode() == null) {
            log.warn("[forwardToThirdParty][第三方SIP节点不存在，跳过转发] callId={}", sessionInfo.getCallId());
            return;
        }
        messageForwarder.forwardToThirdParty(request, sessionInfo.getThirdPartyNode());
        log.info("[forwardToThirdParty][已转发到第三方SIP服务] callId={}, node={}:{}",
                sessionInfo.getCallId(),
                sessionInfo.getThirdPartyNode().getSipIp(),
                sessionInfo.getThirdPartyNode().getSipPort());
    }

    /**
     * Call-ID缺失时的fallback转发
     * 直接按To头查注册转发，保证异常入参下不丢失信令
     *
     * @param request SIP请求
     * @param callId  Call-ID（可能为null）
     * @throws Exception 转发失败或构造错误响应失败时抛出
     */
    private void fallbackForwardByRegistration(Request request, String callId) throws Exception {
        String toUser = SipAnalysisUtil.extractToUser(request);
        String toDomain = SipAnalysisUtil.extractToDomain(request);
        if (!validateToHeader(toUser, toDomain)) {
            sendErrorResponse(callId, request, Response.BAD_REQUEST);
            return;
        }
        forwardRequestByRegistration(request, callId, toUser, toDomain);
    }
}
