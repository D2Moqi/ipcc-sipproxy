package cn.ipcc.sipproxy.core.handler.request.sip;

import cn.ipcc.sipproxy.core.handler.AbstractSipHandler;
import cn.ipcc.sipproxy.core.node.SipNodeManager;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.session.SipSessionManager;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;

/**
 * 传入SIP请求处理器抽象基类
 * 用于处理来自FreeSWITCH/第三方SIP的SIP请求
 *
 * @author ipcc
 */
@Slf4j
public abstract class AbstractSipRequestHandler extends AbstractSipHandler {

    @Resource
    protected SipSessionManager sessionManager;

    @Resource
    protected SipNodeManager nodeManager;

    @Setter
    protected HeaderFactory headerFactory;

    /**
     * 处理传入的SIP请求
     *
     * @param request SIP请求
     * @param callId  Call-ID
     * @param source  消息来源（FREESWITCH或THIRD_PARTY）
     * @throws Exception 处理异常
     */
    public abstract void handle(Request request, String callId, String source) throws Exception;

    /**
     * 验证To头信息
     */
    protected boolean validateToHeader(String toUser, String toDomain) {
        if (toUser == null || toDomain == null) {
            log.error("[validateToHeader][To头信息不完整] toUser={}, toDomain={}", toUser, toDomain);
            return false;
        }
        return true;
    }

    /**
     * 发送错误响应
     */
    protected void sendErrorResponse(String callId, Request request, int statusCode) throws Exception {
        Response errorResponse = SipAnalysisUtil.buildResponse(request, statusCode);
        try {
            Header contentLengthHeader = headerFactory.createHeader("Content-Length", "0");
            errorResponse.addHeader(contentLengthHeader);
            if (callId != null) {
                SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
                if (sessionInfo.getSessionId() != null) {
                    if (sessionInfo.getFreeSwitchNode() != null) {
                        messageForwarder.toWebSocket(sessionInfo.getSessionId(), errorResponse);
                    } else if (sessionInfo.getThirdPartyNode() != null) {
                        messageForwarder.toWebSocket(sessionInfo.getSessionId(), errorResponse);
                    } else {
                        log.error("[sendErrorResponse][会话不存在] callId={}", callId);
                    }
                }
            }
        } catch (ParseException e) {
            log.error("[sendErrorResponse][构造错误响应头失败] statusCode={}", statusCode, e);
            throw e;
        }
    }

    /**
     * 根据用户注册状态转发请求
     * 如果用户已注册，转发到 WebSocket 客户端
     * 如果用户未注册，转发到第三方 SIP 服务
     *
     * @param request   SIP请求
     * @param callId    Call-ID
     * @param toUser    被叫用户名
     * @param toDomain  被叫域名
     */
    protected void forwardRequestByRegistration(Request request, String callId, String toUser, String toDomain) throws Exception {
        boolean isRegistered = isRegisteredUser(toUser, toDomain);
        if (isRegistered) {
            log.info("[forwardRequestByRegistration][转发到WebSocket客户端] callId={}, toUser={}", callId, toUser);
            messageForwarder.forwardToWebSocketByUser(toUser, toDomain, request);
        } else {
            log.info("[forwardRequestByRegistration][转发到第三方SIP服务] callId={}, toUser={}", callId, toUser);
            // 按 INVITE 来源 IP 反查匹配的第三方网关节点
            String sourceIp = SipAnalysisUtil.getSourceIpFromMessage(request);
            // selectThirdPartyNode 返回 GatewayInfo
            GatewayInfo thirdPartyNode = nodeManager.selectThirdPartyNode(callId, sourceIp);
            if (thirdPartyNode != null) {
                messageForwarder.forwardToThirdParty(request, thirdPartyNode);
            }
        }
    }
}
