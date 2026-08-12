package cn.ipcc.sipproxy.core.handler.response;

import cn.ipcc.sipproxy.api.gateway.MessageSourceIdentifier;
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
 * @author ipcc
 */
@Slf4j
public abstract class AbstractSipResponseHandler {

    @Resource
    protected SipSessionManager sessionManager;

    @Resource
    protected SipMessageForwarder messageForwarder;

    @Resource
    protected MessageSourceIdentifier messageSourceIdentifier;


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
        // 关键: 必须使用"校正后的来源"判断。407 响应的来源 IP 是 sipproxy 自身,
        // identifySource 原始识别会因匹配不到网关落到 FS UA 兜底返回 FREESWITCH,
        // 而 SessionInfo 上下文校正(FREESWITCH→THIRD_PARTY)若发生在 determineResponseTarget 内
        // 则晚于本拦截检查,导致出局网关的 407 挑战永远进不了 Digest 重发链路(INVITE 裸发重传至超时)。
        // 通过 resolveSource 钩子先执行子类校正,保证拦截判断与后续转发目标使用同一来源结果
        if (statusCode == Response.PROXY_AUTHENTICATION_REQUIRED) {
            String source = resolveSource(response, sessionInfo);
            String gatewayId = sessionInfo.getGatewayId();
            if (SipProxyConstants.THIRD_PARTY.equals(source) && gatewayId != null && !gatewayId.isEmpty()) {
                boolean retried = messageForwarder.handle407ProxyAuth(response, sessionInfo);
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
     * 识别响应来源(含子类 SessionInfo 上下文校正)
     * <p>
     * 默认实现直接委托 MessageSourceIdentifier 扩展点;子类可覆盖以叠加
     * SessionInfo 上下文校正(如第三方 leg 强制 THIRD_PARTY)。
     * 407 拦截判断与转发目标决策均应使用本方法返回的校正后来源,
     * 避免两处识别结果不一致导致拦截失效。
     *
     * @param response    SIP响应
     * @param sessionInfo 会话信息
     * @return 校正后的来源标识(WEBSOCKET/FREESWITCH/THIRD_PARTY)
     */
    protected String resolveSource(Response response, SessionInfo sessionInfo) {
        return messageSourceIdentifier.identifySource(response);
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
