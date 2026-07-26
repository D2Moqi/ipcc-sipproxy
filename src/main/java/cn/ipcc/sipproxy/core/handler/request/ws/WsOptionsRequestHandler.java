package cn.ipcc.sipproxy.core.handler.request.ws;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.ipcc.sipproxy.api.trace.TraceContext;
import cn.ipcc.sipproxy.core.annotation.SipMethod;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.header.AllowHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * OPTIONS请求处理器
 * 处理OPTIONS保活请求
 *
 * @author 芋道源码
 */
@Slf4j
@Component
@SipMethod(SipAnalysisUtil.OPTIONS)
public class WsOptionsRequestHandler extends AbstractWsSipRequestHandler {

    /**
     * 链路追踪扩展点（可选注入，替代原 TraceUtil 静态调用）
     * <p>
     * 父程序未实现 TraceContext 时跳过，不影响业务流程。
     */
    @Autowired(required = false)
    private TraceContext traceContext;

    @Override
    protected void doHandle(String sessionId, Request request, String callId) throws Exception {
        // 入口设置 traceId,实现全链路日志串联;优先复用 SIP 请求的 Call-ID,缺失时回退到 UUID
        setTraceId(StrUtil.isNotBlank(callId) ? callId : IdUtil.fastSimpleUUID());
        try {
        log.info("[doHandle][开始处理OPTIONS保活请求] sessionId={}", sessionId);
        var response = messageFactory.createResponse(Response.OK, request);
        AllowHeader allowHeader = headerFactory.createAllowHeader(
                SipProxyConstants.SIP_METHODS_SUPPORTED);
        response.addHeader(allowHeader);
        response.addHeader(headerFactory.createHeader("Content-Length", "0"));
        messageForwarder.toWebSocket(sessionId, response);
        log.info("[doHandle][OPTIONS保活请求处理成功] sessionId={}", sessionId);
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
}
