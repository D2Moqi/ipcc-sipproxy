package cn.ipcc.sipproxy.core.handler.request.ws;

import cn.ipcc.sipproxy.core.handler.response.ResponseForwardingStrategy;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Request;

/**
 * 默认SIP请求处理器
 * 处理来自WebSocket的未注册SIP方法（ACK/CANCEL/SUBSCRIBE/PRACK/UPDATE/INFO等），提供默认处理逻辑
 *
 * @author ipcc
 */
@Slf4j
@Component
public class WsDefaultRequestHandler extends AbstractWsSipRequestHandler {

    @Resource
    private ResponseForwardingStrategy responseForwardingStrategy;

    /**
     * 处理来自WebSocket的会话内SIP请求（ACK/CANCEL/SUBSCRIBE/PRACK/UPDATE/INFO等未注册方法）
     *
     * 需求背景:
     *   WebSocket来源的会话内SIP方法（PRACK/UPDATE/INFO等）属于会话建立后的后续信令，
     *   需按会话状态决策转发目标，而非统一转发到 selectFreeSwitchNode 选出的FS。
     *   原逻辑将所有未注册方法统一转发到FS，无法正确处理需要回送第三方的会话内方法
     *   （例如入呼场景下第三方网关发送1xx带SDP响应触发PRACK时，PRACK应按会话决策转发到THIRD_PARTY，
     *   且 RSeq/RAck/Require 等 100rel 可靠性握手头域必须原样透传，否则会导致可靠性握手失败）。
     *
     * 处理逻辑:
     *   1. Call-ID 为空（防御性，正常流程父类已校验非空）: fallback 到原逻辑（selectFreeSwitchNode + forwardToFreeSwitch）
     *   2. 按 Call-ID 查 SessionInfo
     *   3. SessionInfo 存在: 复用 ResponseForwardingStrategy 按 source=WEBSOCKET + callType 决策转发目标，
     *      按 target 分支调用 messageForwarder 转发（头域原样透传，仅 Contact/Via/Request-URI 被改写）：
     *        - FREESWITCH  → forwardToFreeSwitch(freeSwitchNode)
     *        - THIRD_PARTY → forwardToThirdParty(thirdPartyNode)
     *        - WEBSOCKET   → 记录 warn（WS来源不应再转发回WS，避免回环），fallback 到原逻辑
     *        - 未知 target  → fallback 到原逻辑
     *   4. SessionInfo 不存在: fallback 到原逻辑（转发到FS），保证未走 sipproxy 完整建立的会话向后兼容
     *
     * 100rel 头域透传:
     *   messageForwarder.forwardToFreeSwitch / forwardToThirdParty 内部调用 modifyHeadersForForwarding，
     *   该方法仅改写 Contact/Via/Request-URI（removeHeader + addHeader），不移除 RSeq/RAck/Require 等 100rel 头域，
     *   PRACK 的可靠性握手头原样保留，无需额外处理。
     *
     * Fallback 场景:
     *   - Call-ID 为空
     *   - SessionInfo 不存在
     *   - target=WEBSOCKET（WS来源不应转发回WS，避免信令回环）
     *   - 未知 target
     *   - 决策目标对应节点缺失（freeSwitchNode/thirdPartyNode 为 null）
     *
     * @param sessionId WebSocket会话ID
     * @param request   SIP请求
     * @param callId    Call-ID，会话标识（由父类通过 SipAnalysisUtil.getCallId(request) 提取并校验非空）
     * @throws Exception 无可用FS节点或转发失败时抛出
     */
    @Override
    protected void doHandle(String sessionId, Request request, String callId) throws Exception {
        log.info("[doHandle][开始处理{}请求] sessionId={}, callId={}", request.getMethod(), sessionId, callId);

        // Call-ID 缺失时无法按会话决策，直接 fallback 到原逻辑（防御性，正常流程父类已校验非空）
        if (callId == null || callId.isEmpty()) {
            log.warn("[doHandle][Call-ID为空，fallback到原逻辑] method={}", request.getMethod());
            fallbackToFreeSwitch(sessionId, request, callId);
            return;
        }

        // 按 Call-ID 查会话信息
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo == null) {
            // SessionInfo 不存在，说明该会话未经过 sipproxy 完整建立（如未走 INVITE 处理器），
            // fallback 到原逻辑（转发到FS），保证向后兼容
            log.warn("[doHandle][SessionInfo不存在，fallback到原逻辑] callId={}", callId);
            fallbackToFreeSwitch(sessionId, request, callId);
            return;
        }

        // 会话已建立，按 source=WEBSOCKET + callType 决策转发目标
        log.info("[doHandle][会话已建立，按source+callType决策转发] callId={}, callType={}",
                callId, sessionInfo.getCallType());
        String target = responseForwardingStrategy.getForwardingTarget(
                SipProxyConstants.WEBSOCKET, sessionInfo.getCallType());
        log.info("[doHandle][决策转发目标] callId={}, source=WEBSOCKET, callType={}, target={}",
                callId, sessionInfo.getCallType(), target);

        // 按 target 分支转发（头域原样透传，仅 Contact/Via/Request-URI 被改写）
        switch (target) {
            case SipProxyConstants.FREESWITCH:
                forwardToFreeSwitchBySession(request, sessionInfo);
                break;
            case SipProxyConstants.THIRD_PARTY:
                forwardToThirdPartyBySession(request, sessionInfo);
                break;
            case SipProxyConstants.WEBSOCKET:
                // WS来源不应再转发回WS（避免信令回环），fallback 到原逻辑
                log.warn("[doHandle][WS来源不应转发回WS，fallback到原逻辑] callId={}", callId);
                fallbackToFreeSwitch(sessionId, request, callId);
                break;
            default:
                // 未知目标，fallback 到原逻辑兜底
                log.warn("[doHandle][未知转发目标，fallback到原逻辑] target={}, callId={}", target, callId);
                fallbackToFreeSwitch(sessionId, request, callId);
        }
    }

    /**
     * 转发会话内SIP请求到FreeSWITCH（按会话记录的FS节点）
     *
     * 处理逻辑:
     *   1. 校验 sessionInfo.freeSwitchNode 是否存在，缺失则 fallback 到原逻辑（重新选节点）
     *   2. 调用 forwardToFreeSwitch 转发（内部含故障转移和头域改写，100rel 头域原样保留）
     *
     * @param request     SIP请求
     * @param sessionInfo 会话信息
     * @throws Exception 转发失败时抛出
     */
    private void forwardToFreeSwitchBySession(Request request, SessionInfo sessionInfo) throws Exception {
        FsNodeInfo freeSwitchNode = sessionInfo.getFreeSwitchNode();
        if (freeSwitchNode == null) {
            log.warn("[forwardToFreeSwitchBySession][会话记录的FS节点不存在，fallback到原逻辑] callId={}",
                    sessionInfo.getCallId());
            fallbackToFreeSwitch(sessionInfo.getSessionId(), request, sessionInfo.getCallId());
            return;
        }
        messageForwarder.forwardToFreeSwitch(request, freeSwitchNode);
        log.info("[forwardToFreeSwitchBySession][已转发到FreeSWITCH] callId={}, fs={}:{}",
                sessionInfo.getCallId(), freeSwitchNode.getSipIp(), freeSwitchNode.getSipPort());
    }

    /**
     * 转发会话内SIP请求到第三方SIP服务（按会话记录的第三方节点）
     *
     * 处理逻辑:
     *   1. 校验 sessionInfo.thirdPartyNode 是否存在，缺失则 fallback 到原逻辑
     *   2. 调用 forwardToThirdParty 转发（内部含头域改写，100rel 头域原样保留）
     *
     * @param request     SIP请求
     * @param sessionInfo 会话信息
     * @throws Exception 转发失败时抛出
     */
    private void forwardToThirdPartyBySession(Request request, SessionInfo sessionInfo) throws Exception {
        GatewayInfo thirdPartyNode = sessionInfo.getThirdPartyNode();
        if (thirdPartyNode == null) {
            log.warn("[forwardToThirdPartyBySession][会话记录的第三方节点不存在，fallback到原逻辑] callId={}",
                    sessionInfo.getCallId());
            fallbackToFreeSwitch(sessionInfo.getSessionId(), request, sessionInfo.getCallId());
            return;
        }
        messageForwarder.forwardToThirdParty(request, thirdPartyNode);
        log.info("[forwardToThirdPartyBySession][已转发到第三方SIP服务] callId={}, tp={}:{}",
                sessionInfo.getCallId(), thirdPartyNode.getAddress(), thirdPartyNode.getPort());
    }

    /**
     * 原逻辑兜底转发：按 callId 选择 FS 节点并转发
     *
     * 设计意图:
     *   保留改造前的处理逻辑作为 fallback，覆盖 Call-ID 为空、SessionInfo 不存在、
     *   target=WEBSOCKET、未知 target、决策节点缺失等异常场景，保证信令不丢失。
     *
     * @param sessionId WebSocket会话ID（仅用于上下文标识）
     * @param request   SIP请求
     * @param callId    Call-ID（可能为null）
     * @throws Exception 无可用FS节点或转发失败时抛出
     */
    private void fallbackToFreeSwitch(String sessionId, Request request, String callId) throws Exception {
        FsNodeInfo freeSwitchNode = nodeManager.selectFreeSwitchNode(callId);
        if (freeSwitchNode == null) {
            log.error("[fallbackToFreeSwitch][没有可用的FreeSWITCH节点] sessionId={}, callId={}", sessionId, callId);
            throw new Exception("没有可用的FreeSWITCH节点");
        }
        messageForwarder.forwardToFreeSwitch(request, freeSwitchNode);
        log.info("[fallbackToFreeSwitch][{}请求已转发到FreeSWITCH] sessionId={}, callId={}, fs={}:{}",
                request.getMethod(), sessionId, callId, freeSwitchNode.getSipIp(), freeSwitchNode.getSipPort());
    }

}
