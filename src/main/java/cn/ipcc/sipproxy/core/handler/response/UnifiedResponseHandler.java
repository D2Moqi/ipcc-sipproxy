package cn.ipcc.sipproxy.core.handler.response;

import cn.ipcc.sipproxy.api.gateway.MessageSourceIdentifier;
import cn.ipcc.sipproxy.core.forwarder.SipMessageForwarder;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Message;
import javax.sip.message.Response;

/**
 * 统一SIP响应处理器
 * 使用策略模式处理来自不同来源的SIP响应
 *
 * @author ipcc
 */
@Slf4j
@Component
public class UnifiedResponseHandler extends AbstractSipResponseHandler {

    @Resource
    private ResponseForwardingStrategy forwardingStrategy;

    @Resource
    private MessageSourceIdentifier messageSourceIdentifier;

    @Override
    protected String determineResponseTarget(Response response, SessionInfo sessionInfo) {
        // 1. 委托 MessageSourceIdentifier 扩展点识别响应来源（WEBSOCKET/FREESWITCH/THIRD_PARTY）
        //    Response 场景下 Via received 参数是 sipproxy 自身 IP,识别结果可能存在偏差,
        //    特别是第三方网关也是 FreeSWITCH 部署时(UA 相同),会被误识别为 FREESWITCH
        String source = messageSourceIdentifier.identifySource(response);

        // 2. SessionInfo 上下文冲突校正（优先级高于 identifySource）
        //    场景: sipproxy 发起 INVITE 时已将目标节点存入 SessionInfo,
        //    因此 Response 的真实来源可以反向推断——"刚刚发给了谁,Response 就来自谁"。
        //    这是 Response 来源识别最可靠的依据,优于 UA/Via 等间接特征。
        source = correctSourceBySessionContext(source, sessionInfo);

        String callType = sessionInfo.getCallType();
        String target = forwardingStrategy.getForwardingTarget(source, callType);
        log.debug("[determineResponseTarget][source={}, callType={}, target={}, callId={}]",
                source, callType, target, sessionInfo.getCallId());

        return target;
    }

    /**
     * 基于 SessionInfo 上下文对 Response 来源做二次校正
     * <p>
     * 校正规则（仅当 SessionInfo 中已缓存明确的节点信息时生效，不做覆盖式校正，
     * 只处理与上下文明显冲突的情况）：
     * <ol>
     *   <li>第三方 leg: {@code sessionInfo.thirdPartyNode != null} 或
     *       {@code sessionInfo.gatewayId != null} → 强制来源 = THIRD_PARTY
     *       （解决第三方网关也是 FS 部署时 UA 相同导致误识别为 FREESWITCH 的场景，
     *       典型如场景4/5/6/7的 c-leg 出局到 gw3（FS 型 SBC）时返回的 100/180/200）</li>
     *   <li>自有 FS leg: {@code sessionInfo.freeSwitchNode != null} 且无 thirdPartyNode/gatewayId
     *       → 识别结果为 WEBSOCKET 时校正为 FREESWITCH
     *       （解决 Response 场景下 Via received 是 sipproxy 自身 IP，UA 匹配失败兜底到 WEBSOCKET
     *       导致 200 OK 被错误转发回 FS 的场景——此问题曾导致 JsSIP 收不到 200 OK 持续重传
     *       INVITE、FS 收不到 ACK、CHANNEL_ANSWER 延迟约 32 秒）</li>
     *   <li>其余情况：保持 identifySource 的原始识别结果不变</li>
     * </ol>
     * <p>
     * 设计原则：校正仅处理与上下文明显冲突的情况，不做"过度校正"；
     * 如 SessionInfo 中 thirdPartyNode 与 freeSwitchNode 同时存在（异常边界场景），
     * 以 thirdPartyNode 为准——明确指定的出局网关优先级更高。
     *
     * @param source      identifySource 返回的初始来源识别结果
     * @param sessionInfo 当前会话上下文（已缓存 freeSwitchNode / thirdPartyNode / gatewayId）
     * @return 校正后的来源标识
     */
    private String correctSourceBySessionContext(String source, SessionInfo sessionInfo) {
        boolean hasThirdParty = sessionInfo.getThirdPartyNode() != null
                || cn.hutool.core.util.StrUtil.isNotBlank(sessionInfo.getGatewayId());
        boolean hasFreeSwitch = sessionInfo.getFreeSwitchNode() != null;

        // 校正1: 明确存在第三方 leg → 强制来源为 THIRD_PARTY
        // (覆盖 FS 型 SBC 返回的 100 Trying/183/200 OK 被 FS UA 兜底误识别)
        if (hasThirdParty) {
            if (!SipProxyConstants.THIRD_PARTY.equals(source)) {
                log.info("[correctSourceBySessionContext][第三方leg校正] callId={}, 原来源={} → 校正为=THIRD_PARTY " +
                                "(thirdPartyNode={}, gatewayId={})",
                        sessionInfo.getCallId(), source,
                        sessionInfo.getThirdPartyNode() != null
                                ? sessionInfo.getThirdPartyNode().getName() : null,
                        sessionInfo.getGatewayId());
            }
            return SipProxyConstants.THIRD_PARTY;
        }

        // 校正2: 仅存在自有 FS leg → 初始识别为 WEBSOCKET 时校正为 FREESWITCH
        // (解决 Response Via received 为 sipproxy 自身 IP,UA 兜底为 WEBSOCKET 的误识别)
        if (hasFreeSwitch && SipProxyConstants.WEBSOCKET.equals(source)) {
            log.info("[correctSourceBySessionContext][自有FSleg校正] callId={}, 原来源=WEBSOCKET → 校正为=FREESWITCH " +
                            "(freeSwitchNode={})",
                    sessionInfo.getCallId(), sessionInfo.getFreeSwitchNode().getName());
            return SipProxyConstants.FREESWITCH;
        }

        return source;
    }

    @Override
    protected void forwardResponse(Response response, String target, SessionInfo sessionInfo) throws Exception {
        try {
            switch (target) {
                case SipProxyConstants.WEBSOCKET:
                    forwardToWebSocket(response, sessionInfo);
                    break;

                case SipProxyConstants.FREESWITCH:
                    forwardToFreeSwitch(response, sessionInfo);
                    break;

                case SipProxyConstants.THIRD_PARTY:
                    forwardToThirdParty(response, sessionInfo);
                    break;

                default:
                    log.warn("[forwardResponse][未知的转发目标] target={}, callId={}", target, sessionInfo.getCallId());
            }
        } catch (Exception e) {
            log.error("[forwardResponse][转发响应失败] target={}, callId={}", target, sessionInfo.getCallId(), e);
            throw e;
        }
    }

    /**
     * 转发到 WebSocket
     */
    private void forwardToWebSocket(Response response, SessionInfo sessionInfo) throws Exception {
        Message wsResponse = messageForwarder.modifyWsProxyHeaders(response);
        String sessionId = sessionInfo.getSessionId();
        if (sessionId != null) {
            messageForwarder.toWebSocket(sessionId, wsResponse);
            log.info("[forwardToWebSocket][转发响应到WebSocket] sessionId={}, callId={}", sessionId, sessionInfo.getCallId());
        } else {
            log.warn("[forwardToWebSocket][未找到WebSocket会话ID] callId={}", sessionInfo.getCallId());
        }
    }

    /**
     * 转发到 FreeSWITCH
     */
    private void forwardToFreeSwitch(Response response, SessionInfo sessionInfo) throws Exception {
        if (sessionInfo.getFreeSwitchNode() != null) {
            messageForwarder.forwardToFreeSwitch(response, sessionInfo.getFreeSwitchNode());
            log.info("[forwardToFreeSwitch][转发响应到FreeSWITCH] node={}, callId={}",
                    sessionInfo.getFreeSwitchNode().getName(), sessionInfo.getCallId());
        } else {
            log.warn("[forwardToFreeSwitch][未找到FreeSWITCH节点] callId={}", sessionInfo.getCallId());
        }
    }

    /**
     * 转发到第三方 SIP 服务
     */
    private void forwardToThirdParty(Response response, SessionInfo sessionInfo) throws Exception {
        if (sessionInfo.getThirdPartyNode() != null) {
            messageForwarder.forwardToThirdParty(response, sessionInfo.getThirdPartyNode());
            log.info("[forwardToThirdParty][转发响应到第三方SIP服务] node={}, callId={}",
                    sessionInfo.getThirdPartyNode().getName(), sessionInfo.getCallId());
        } else {
            log.warn("[forwardToThirdParty][未找到第三方SIP服务节点] callId={}", sessionInfo.getCallId());
        }
    }
}
