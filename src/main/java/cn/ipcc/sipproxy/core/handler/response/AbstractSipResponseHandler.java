package cn.ipcc.sipproxy.core.handler.response;

import cn.ipcc.sipproxy.core.forwarder.SipMessageForwarder;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.session.SipSessionManager;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import javax.sip.message.Response;

/**
 * SIP响应处理器抽象基类
 * 定义SIP响应处理的标准流程
 *
 * @author 芋道源码
 */
@Slf4j
public abstract class AbstractSipResponseHandler {

    @Resource
    protected SipSessionManager sessionManager;

    @Resource
    protected SipMessageForwarder messageForwarder;


    /**
     * 处理SIP响应
     *
     * 处理流程：
     * 1. 提取状态码和Call-ID，根据Call-ID获取会话信息
     * 2. 若为407 Proxy Authentication Required且来源为第三方网关，尝试注入鉴权重发INVITE：
     *    - 重发成功则拦截407不转发到坐席
     *    - 重发失败则继续正常转发407让坐席感知错误
     * 3. 非拦截场景走标准流程：确定转发目标 → 转发响应
     *
     * @param response SIP响应
     * @throws Exception 处理异常
     */
    public void handle(Response response) throws Exception {
        int statusCode = response.getStatusCode();
        String callId = SipAnalysisUtil.getCallId(response);

        log.info("[handle][收到SIP响应] statusCode={}, callId={}", statusCode, callId);

        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo == null) {
            log.warn("[handle][未找到对应的会话信息] callId={}", callId);
            return;
        }

        // 407 Proxy Authentication Required拦截处理：第三方网关要求代理鉴权时，注入Proxy-Authorization重发INVITE
        if (statusCode == Response.PROXY_AUTHENTICATION_REQUIRED) {
            String source = messageForwarder.identifyMessageSource(response);
            String gatewayId = sessionInfo.getGatewayId();
            if (SipProxyConstants.THIRD_PARTY.equals(source) && gatewayId != null && !gatewayId.isEmpty()) {
                // originalInvite传null，handle407ProxyAuth内部从sessionInfo.originalInviteContent解析重建
                boolean retried = messageForwarder.handle407ProxyAuth(response, sessionInfo, null);
                if (retried) {
                    log.info("[handle][407鉴权重发成功，拦截407不转发到坐席] callId={}", callId);
                    return;
                }
                log.warn("[handle][407鉴权重发失败，正常转发407到坐席] callId={}", callId);
            }
        }

        String target = determineResponseTarget(response, sessionInfo);
        log.info("[handle][转发目标={}, callId={}]", target, callId);

        forwardResponse(response, target, sessionInfo);
    }

    /**
     * 决定响应转发目标
     *
     * @param response    SIP响应
     * @param sessionInfo 会话信息
     * @return 转发目标（WEBSOCKET、FREESWITCH、THIRD_PARTY）
     */
    protected abstract String determineResponseTarget(Response response, SessionInfo sessionInfo);

    /**
     * 转发响应
     *
     * @param response    SIP响应
     * @param target      转发目标
     * @param sessionInfo 会话信息
     * @throws Exception 转发异常
     */
    protected abstract void forwardResponse(Response response, String target, SessionInfo sessionInfo) throws Exception;

}
