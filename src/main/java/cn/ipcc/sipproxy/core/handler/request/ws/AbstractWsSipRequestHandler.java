package cn.ipcc.sipproxy.core.handler.request.ws;

import cn.ipcc.sipproxy.core.handler.AbstractSipHandler;
import cn.ipcc.sipproxy.core.node.SipNodeManager;
import cn.ipcc.sipproxy.core.session.SipSessionManager;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyErrorCodeConstants;
import cn.ipcc.sipproxy.support.SipProxyException;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;

/**
 * SIP请求处理器基类
 * 使用模板方法模式，定义SIP请求处理的标准流程
 *
 * @author 芋道源码
 */
@Slf4j
public abstract class AbstractWsSipRequestHandler extends AbstractSipHandler {

    @Resource
    protected SipSessionManager sessionManager;

    @Resource
    protected SipNodeManager nodeManager;

    @Setter
    protected MessageFactory messageFactory;

    @Setter
    protected HeaderFactory headerFactory;

    public final void handle(String sessionId, Request request) throws Exception {
        String callId = SipAnalysisUtil.getCallId(request);
        if (callId == null) {
            log.error("[handle][Call-ID为空，拒绝请求] method={}", request.getMethod());
            sendErrorResponse(sessionId, request, Response.BAD_REQUEST);
            return;
        }

        log.info("[handle][开始处理{}请求] sessionId={}, callId={}", request.getMethod(), sessionId, callId);

        try {
            validateRequest(request);
            doHandle(sessionId, request, callId);
        } catch (Exception e) {
            log.error("[handle][处理{}请求异常] sessionId={}, callId={}",
                    request.getMethod(), sessionId, callId, e);
            sendErrorResponse(sessionId, request, Response.SERVER_INTERNAL_ERROR);
        }
    }

    protected abstract void doHandle(String sessionId, Request request, String callId) throws Exception;

    /**
     * 校验请求是否有效
     *
     * @param request 请求
     * @throws SipProxyException 校验失败时，抛出异常（原为 ServiceException，迁移后改为 SipProxyException）
     */
    protected void validateRequest(Request request) throws SipProxyException {
        String toUser = SipAnalysisUtil.extractToUser(request);
        String toDomain = SipAnalysisUtil.extractToDomain(request);

        if (!StringUtils.hasText(toUser) || !StringUtils.hasText(toDomain)) {
            throw new SipProxyException(SipProxyErrorCodeConstants.INTERNAL_SERVER_ERROR, "无效的请求信息");
        }
    }


    /**
     * 发送100 Trying响应
     *
     * @param sessionId Session 编号
     * @param request   原始请求
     */
    protected void sendTryingResponse(String sessionId, Request request) throws Exception {
        Response tryingResponse = SipAnalysisUtil.buildResponse(request, Response.TRYING);
        try {
            Header contentLengthHeader = headerFactory.createHeader("Content-Length", "0");
            tryingResponse.addHeader(contentLengthHeader);
            messageForwarder.toWebSocket(sessionId, tryingResponse);
            log.debug("[sendTryingResponse][已发送100 Trying响应] sessionId={}", sessionId);
        } catch (ParseException e) {
            log.error("[sendTryingResponse][构造100 Trying响应头失败]", e);
            throw e;
        }
    }

    /**
     * 发送错误响应
     *
     * @param sessionId  Session 编号
     * @param request    原始请求
     * @param statusCode 错误状态码
     */
    protected void sendErrorResponse(String sessionId, Request request, int statusCode) throws Exception {
        Response errorResponse = SipAnalysisUtil.buildResponse(request, statusCode);
        try {
            Header contentLengthHeader = headerFactory.createHeader("Content-Length", "0");
            errorResponse.addHeader(contentLengthHeader);
            messageForwarder.toWebSocket(sessionId, errorResponse);
        } catch (ParseException e) {
            log.error("[sendErrorResponse][构造错误响应头失败] statusCode={}", statusCode, e);
            throw e;
        }
    }
}
