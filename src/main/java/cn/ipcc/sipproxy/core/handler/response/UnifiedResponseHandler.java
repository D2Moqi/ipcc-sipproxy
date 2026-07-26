package cn.ipcc.sipproxy.core.handler.response;

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
 * @author 芋道源码
 */
@Slf4j
@Component
public class UnifiedResponseHandler extends AbstractSipResponseHandler {

    @Resource
    private ResponseForwardingStrategy forwardingStrategy;

    @Override
    protected String determineResponseTarget(Response response, SessionInfo sessionInfo) {
        String source = messageForwarder.identifyMessageSource(response);
        String callType = sessionInfo.getCallType();
        
        String target = forwardingStrategy.getForwardingTarget(source, callType);
        log.debug("[determineResponseTarget][source={}, callType={}, target={}]", source, callType, target);
        
        return target;
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
            messageForwarder.forwardToWebSocket(sessionId, wsResponse);
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
