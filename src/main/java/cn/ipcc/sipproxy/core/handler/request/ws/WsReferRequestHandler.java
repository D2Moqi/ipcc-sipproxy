package cn.ipcc.sipproxy.core.handler.request.ws;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.ipcc.sipproxy.api.trace.TraceContext;
import cn.ipcc.sipproxy.core.annotation.SipMethod;
import cn.ipcc.sipproxy.core.interceptor.SipMessageInterceptor;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.header.Header;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * SIP REFER请求处理器(WebSocket来源)
 * <p>
 * 迁移说明：原 cc-server 实现直接依赖 FsClient/FsCallCacheService/CallInfo/ChannelInfo 等 ESL 内部类，
 * 完成咨询转接与盲转的 ESL 编排（originate/bridge/hold/kill）。
 * sipproxy 模块解耦后不再连接 FreeSWITCH，所有 ESL 操作通过 SipMessageInterceptor 扩展点委托父程序实现。
 * <p>
 * 新的处理流程：
 *   1. 检测 SipMessageInterceptor Bean 是否存在（可选注入）
 *   2. 存在则委托 preWsToSip(request)：
 *      - 返回 true 表示父程序已接管 REFER 处理（ESL 编排），sipproxy 仅回复 202 Accepted
 *      - 返回 false 表示父程序未接管，sipproxy 按默认转发逻辑（转发到 FS）
 *   3. 不存在 SipMessageInterceptor 时，按默认转发逻辑（转发到 FS）
 *
 * @author 芋道源码
 */
@Slf4j
@Component
@SipMethod(SipAnalysisUtil.REFER)
public class WsReferRequestHandler extends AbstractWsSipRequestHandler {

    /**
     * SIP 消息拦截器扩展点（可选注入）
     * <p>
     * 父程序可实现此接口，在 REFER 转发前注入 ESL 编排逻辑（咨询转接/盲转）。
     * 返回 true 表示已接管，sipproxy 不再转发；返回 false 表示继续走默认转发逻辑。
     * 未实现时为 null，sipproxy 按默认逻辑转发到 FS。
     * <p>
     * 实现说明：jakarta.annotation.Resource 不支持 required=false，
     * 改用 Spring 的 @Autowired(required = false) 实现可选注入。
     */
    @Autowired(required = false)
    private SipMessageInterceptor sipMessageInterceptor;

    /**
     * 链路追踪扩展点（可选注入，替代原 TraceUtil 静态调用）
     */
    @Autowired(required = false)
    private TraceContext traceContext;

    /**
     * 处理WebSocket来源的REFER请求
     *
     * 需求: 坐席A与坐席B通话中,坐席A发送REFER请求发起转接,将B转接给外部目标C
     * 预期结果: 父程序通过 SipMessageInterceptor 接管时,直接回复 202 Accepted;
     *          父程序未接管时,sipproxy 按默认逻辑转发 REFER 到 FS
     * 处理逻辑:
     *   1. 设置 traceId（如父程序实现了 TraceContext）
     *   2. 校验 Refer-To 头非空
     *   3. 检测 SipMessageInterceptor 是否存在:
     *      - 存在且 preWsToSip 返回 true: 父程序已接管,回复 202 Accepted 后返回
     *      - 不存在或返回 false: 按默认逻辑转发到 FS
     *
     * @param sessionId WebSocket会话ID
     * @param request   SIP REFER请求
     * @param callId    Call-ID
     */
    @Override
    protected void doHandle(String sessionId, Request request, String callId) throws Exception {
        // 入口设置 traceId,实现全链路日志串联;优先复用 SIP 请求的 Call-ID,缺失时回退到 UUID
        setTraceId(StrUtil.isNotBlank(callId) ? callId : IdUtil.fastSimpleUUID());
        try {
        log.info("[doHandle][REFER] callId={}, 开始处理转接请求", callId);

        // 解析REFER请求中的关键头域
        String referTo = extractReferTo(request);
        String gatewayId = extractHeader(request, "X-Gateway-Id");
        String transferType = extractHeader(request, "X-Transfer-Type");

        // 校验Refer-To目标不能为空
        if (StrUtil.isBlank(referTo)) {
            log.error("[doHandle][REFER] Refer-To为空, callId={}", callId);
            sendErrorResponse(sessionId, request, Response.BAD_REQUEST);
            return;
        }

        // 默认为盲转
        if (StrUtil.isBlank(transferType)) {
            transferType = "blind";
        }

        log.info("[doHandle][REFER] callId={}, referTo={}, gatewayId={}, transferType={}",
                callId, referTo, gatewayId, transferType);

        // 检测 SipMessageInterceptor 是否存在（父程序接管 ESL 编排场景）
        if (sipMessageInterceptor != null && sipMessageInterceptor.preWsToSip(request)) {
            // 父程序已接管 REFER 处理（ESL 编排:hold→originate→bridge→kill），sipproxy 仅回复 202 Accepted
            log.info("[doHandle][REFER] 父程序拦截器已接管, callId={}", callId);
            sendAcceptedResponse(sessionId, request);
            return;
        }

        // 父程序未接管时,按默认逻辑转发到 FS（保留原 fallback 逻辑）
        // 说明: sipproxy 不直接调用 FsClient 编排 ESL,由 FS 收到 REFER 后按其内置转接逻辑处理,
        //       或由父程序通过 SipMessageInterceptor.preSipToWs / ESL 事件处理器接管后续流程
        log.info("[doHandle][REFER] 父程序未接管,按默认逻辑转发到FS, callId={}", callId);
        forwardToFreeSwitchByDefault(request, callId);

        // 默认转发后仍回复 202 Accepted（RFC 3515 要求）
        sendAcceptedResponse(sessionId, request);
        } finally {
            // 清理当前线程 traceId,避免线程复用导致日志串扰
            clearTraceId();
        }
    }

    /**
     * 默认转发逻辑：选择 FS 节点并转发 REFER 请求
     * <p>
     * 设计意图：父程序未实现 SipMessageInterceptor 或拦截器返回 false 时，
     * sipproxy 按"透明转发"语义将 REFER 转发到会话绑定的 FS 节点，
     * 由 FS 内置转接逻辑或父程序 ESL 事件处理器完成后续话务操作。
     *
     * @param request SIP REFER 请求
     * @param callId  Call-ID
     * @throws Exception 转发失败时抛出
     */
    private void forwardToFreeSwitchByDefault(Request request, String callId) throws Exception {
        FsNodeInfo freeSwitchNode = nodeManager.selectFreeSwitchNode(callId);
        if (freeSwitchNode == null) {
            log.error("[forwardToFreeSwitchByDefault][没有可用的FreeSWITCH节点] callId={}", callId);
            throw new Exception("没有可用的FreeSWITCH节点");
        }
        messageForwarder.forwardToFreeSwitch(request, freeSwitchNode);
        log.info("[forwardToFreeSwitchByDefault][REFER已转发到FreeSWITCH] callId={}, fs={}:{}",
                callId, freeSwitchNode.getSipIp(), freeSwitchNode.getSipPort());
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
     * 从REFER请求中提取Refer-To头域的目标号码
     *
     * 需求: REFER请求通过Refer-To头指定转接目标,格式为 <sip:number@domain>
     * 预期结果: 提取出目标号码(如13800138000)
     * 处理逻辑:
     *   1. 获取Refer-To头域的值
     *   2. 解析sip URI,提取user部分作为目标号码
     *
     * @param request SIP REFER请求
     * @return 转接目标号码,解析失败返回null
     */
    private String extractReferTo(Request request) {
        try {
            Header referToHeader = request.getHeader("Refer-To");
            if (referToHeader == null) {
                return null;
            }
            String headerStr = referToHeader.toString();
            // Refer-To格式: "Refer-To: <sip:13800138000@domain>" 或 "Refer-To: sip:13800138000@domain"
            // 提取sip:后面的user部分
            int sipIndex = headerStr.indexOf("sip:");
            if (sipIndex < 0) {
                return null;
            }
            String afterSip = headerStr.substring(sipIndex + 4);
            // 去掉@domain及之后的部分
            int atIndex = afterSip.indexOf('@');
            if (atIndex > 0) {
                return afterSip.substring(0, atIndex).trim();
            }
            // 去掉>和空格
            int gtIndex = afterSip.indexOf('>');
            if (gtIndex > 0) {
                return afterSip.substring(0, gtIndex).trim();
            }
            return afterSip.trim();
        } catch (Exception e) {
            log.error("[extractReferTo] 提取Refer-To头域失败", e);
            return null;
        }
    }

    /**
     * 从SIP请求头中提取指定自定义头域的值
     *
     * 需求: REFER请求可能携带X-Gateway-Id、X-Transfer-Type等自定义头域
     * 预期结果: 返回头域的值,不存在时返回null
     * 处理逻辑: 通过JAIN-SIP API获取指定头域,提取冒号后的值并去除前后空白
     *
     * @param request    SIP请求
     * @param headerName 头域名称
     * @return 头域值,不存在时返回null
     */
    private String extractHeader(Request request, String headerName) {
        try {
            Header header = request.getHeader(headerName);
            if (header != null) {
                String headerStr = header.toString();
                int colonIndex = headerStr.indexOf(':');
                if (colonIndex >= 0 && colonIndex < headerStr.length() - 1) {
                    return headerStr.substring(colonIndex + 1).trim();
                }
            }
        } catch (Exception e) {
            log.warn("[extractHeader] 提取{}头域异常", headerName, e);
        }
        return null;
    }

    /**
     * 发送202 Accepted响应给坐席A
     *
     * 需求: RFC 3515规定REFER请求必须回复202 Accepted
     * 预期结果: 坐席A收到202 Accepted响应,知道转接请求已被接受
     * 处理逻辑: 构造202 Accepted响应并通过WebSocket发送
     *
     * @param sessionId WebSocket会话ID
     * @param request   原始REFER请求
     */
    private void sendAcceptedResponse(String sessionId, Request request) throws Exception {
        Response acceptedResponse = SipAnalysisUtil.buildResponse(request, Response.ACCEPTED);
        try {
            Header contentLengthHeader = headerFactory.createHeader("Content-Length", "0");
            acceptedResponse.addHeader(contentLengthHeader);
            messageForwarder.toWebSocket(sessionId, acceptedResponse);
            log.info("[sendAcceptedResponse] 已发送202 Accepted响应, sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("[sendAcceptedResponse] 构造202 Accepted响应失败", e);
            throw e;
        }
    }
}
