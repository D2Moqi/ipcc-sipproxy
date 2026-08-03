package cn.ipcc.sipproxy.core.handler.request.sip;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.ipcc.sipproxy.api.trace.TraceContext;
import cn.ipcc.sipproxy.core.annotation.SipMethod;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.model.AgentInfo;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.header.Header;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * 传入INVITE请求处理器
 * 处理来自FreeSWITCH/第三方SIP的INVITE请求
 *
 * @author ipcc
 */
@Slf4j
@Component
@SipMethod(SipAnalysisUtil.INVITE)
public class SipInviteRequestHandler extends AbstractSipRequestHandler {

    /**
     * 链路追踪扩展点（可选注入）
     * <p>
     * 替代原 TraceUtil.setTraceId 静态调用。父程序未实现 TraceContext 时为 null，
     * 跳过 traceId 设置，不影响业务流程。
     * <p>
     * 实现说明：jakarta.annotation.Resource 不支持 required=false，
     * 改用 Spring 的 @Autowired(required = false) 实现可选注入。
     */
    @Autowired(required = false)
    private TraceContext traceContext;

    /**
     * 处理传入INVITE请求
     *
     * 需求:处理来自FreeSWITCH/第三方SIP的INVITE请求,默认统一转发到内部FS park走号码路由匹配→IVR流程;
     *      但保留"FS源+携带X-Gateway-Id"的快速出局豁免,直接转发到指定出局网关(场景四/五/六/七)
     * 预期结果:
     *   - FS源+携带X-Gateway-Id:直接走forwardToOutboundGateway,跳过FS park
     *   - 其他场景:统一通过forwardToFreeSwitch转发到内部FS park,由ESL处理器走号码路由匹配→IVR流程
     * 处理逻辑:
     *   1. 提取To/From头信息并校验
     *   2. 提取X-Gateway-Id(作为后续IVR转接节点的网关覆盖项,不再用于路由决策)
     *   3. 根据消息来源(FREESWITCH/THIRD_PARTY)与是否携带X-Gateway-Id选择转发目标FS节点并设置callType
     *      callType 标记规则:
     *        - FREESWITCH+携带X-Gateway-Id → OUTBOUND(c-leg 出局腿,响应需回送FS)
     *        - FREESWITCH+未携带X-Gateway-Id → INTERNAL(FS 内部回环)
     *        - THIRD_PARTY → INBOUND(响应转发回第三方)
     *   4. 豁免场景(FS源+携带X-Gateway-Id)直接调用forwardToOutboundGateway,不再走FS park
     *      适用豁免场景:三方会议c-leg、双向出局转接、REFER转接外部、自动外呼
     *   5. 默认场景通过forwardToFreeSwitch转发到内部FS park,由ESL处理器走号码路由匹配→IVR流程
     *   6. X-Gateway-Id头透传到FS,最终到ESL事件,作为IVR转接节点的网关覆盖项
     *
     * @param request SIP INVITE请求
     * @param callId  Call-ID
     * @param source  消息来源(FREESWITCH或THIRD_PARTY),用于决定转发目标FS节点与响应转发方向
     */
    @Override
    public void handle(Request request, String callId, String source) throws Exception {
        // 入口设置 traceId,实现全链路日志串联;优先复用 SIP 请求的 Call-ID,缺失时回退到 UUID
        // 替代原 TraceUtil.setTraceId 静态调用，改为可选注入的 TraceContext
        setTraceId(StrUtil.isNotBlank(callId) ? callId : IdUtil.fastSimpleUUID());
        try {
        log.info("[handleIncomingRequest][处理传入INVITE请求] callId={}, source={}", callId, source);

        String toUser = SipAnalysisUtil.extractToUser(request);
        String toDomain = SipAnalysisUtil.extractToDomain(request);
        String fromUser = SipAnalysisUtil.extractFromUser(request);

        if (!validateToHeader(toUser, toDomain)) {
            sendErrorResponse(callId, request, Response.BAD_REQUEST);
            return;
        }

        // 提取X-Gateway-Id(作为后续IVR转接节点的网关覆盖项,不再用于路由决策)
        String gatewayId = extractGatewayId(request);

        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo == null) {
            sessionInfo = new SessionInfo(callId);
            String transport = SipAnalysisUtil.getTransportFromVia(request);
            sessionInfo.setToSipTransport(transport);
            log.info("[handleIncomingRequest][检测到传输协议] callId={}, transport={}", callId, transport);
            // 号码分析驱动路由:所有INVITE统一走FS park→号码路由匹配→IVR流程
            // X-Gateway-Id仅作为后续IVR转接节点的网关覆盖项,不再用于路由决策
            // 根据消息来源(FREESWITCH/THIRD_PARTY)与是否携带X-Gateway-Id决定callType(响应转发方向)
            // callType 标记规则:
            //   - FREESWITCH + 携带X-Gateway-Id → OUTBOUND(c-leg 出局腿,响应需回送FS)
            //   - FREESWITCH + 未携带X-Gateway-Id → INTERNAL(FS 内部回环,如REFER内部转接)
            //   - THIRD_PARTY → INBOUND(响应转发回第三方)
            //   - 其他 → INTERNAL
            String callType;
            if (SipProxyConstants.FREESWITCH.equals(source) && StrUtil.isNotBlank(gatewayId)) {
                // FS c-leg 携带 X-Gateway-Id → 出局腿(场景四/五/六/七:三方会议c-leg、双向出局转接、REFER转接外部、自动外呼)
                callType = SipProxyConstants.CALL_TYPE_OUTBOUND;
            } else if (SipProxyConstants.FREESWITCH.equals(source)) {
                // FS 内部回环(未携带X-Gateway-Id,内部转接等场景)
                callType = SipProxyConstants.CALL_TYPE_INTERNAL;
            } else if (SipProxyConstants.THIRD_PARTY.equals(source)) {
                // 第三方网关入局:按 INVITE 来源 IP 反查匹配的第三方网关节点并缓存(用于响应时回送),callType=INBOUND
                String sourceIp = SipAnalysisUtil.getSourceIpFromMessage(request);
                GatewayInfo thirdPartyNode = nodeManager.selectThirdPartyNode(callId, sourceIp);
                if (thirdPartyNode != null) {
                    sessionInfo.setThirdPartyNode(thirdPartyNode);
                }
                callType = SipProxyConstants.CALL_TYPE_INBOUND;
            } else {
                // 其他来源默认按内部呼叫处理
                callType = SipProxyConstants.CALL_TYPE_INTERNAL;
            }
            // 选择内部FS节点作为转发目标(FS park锚定媒体)
            FsNodeInfo freeSwitchNode = nodeManager.selectFreeSwitchNode(callId);
            if (freeSwitchNode != null) {
                sessionInfo.setFreeSwitchNode(freeSwitchNode);
            }
            // 保存X-Gateway-Id到SessionInfo(作为后续IVR转接节点的网关覆盖项)
            if (gatewayId != null) {
                sessionInfo.setGatewayId(gatewayId);
                log.info("[handleIncomingRequest][提取到网关ID,作为IVR覆盖项] callId={}, gatewayId={}", callId, gatewayId);
            }
            sessionInfo.setCallType(callType);
            sessionManager.cacheSessionInfo(sessionInfo);
            log.info("[handleIncomingRequest][创建会话信息] callId={}, callType={}, fromUser={}, toUser={}, source={}, transport={}",
                    callId, sessionInfo.getCallType(), fromUser, toUser, source, transport);
        } else {
            log.warn("[handleIncomingRequest][Invite 会话信息 已存在 ] callId={}, source={}", callId, source);
        }

        // 豁免场景(快速出局):FS 源 INVITE 携带 X-Gateway-Id 时,直接转发到出局网关,
        // 跳过 FS park→号码路由匹配→IVR 流程,恢复场景四/五/六/七的快速出局能力
        // 适用场景:
        //   场景四: 三方会议邀请外部手机的 c-leg(FS 主动带 gw3 外呼)
        //   场景五: 双向出局转接的 a-leg/b-leg(FS 已确定出局目标,无需再走号码路由)
        //   场景六: REFER 转接外部(携带 gw3)的 c-leg
        //   场景七: 自动外呼的 a-leg(FS 按预拨号计划外呼)
        if (SipProxyConstants.FREESWITCH.equals(source) && StrUtil.isNotBlank(gatewayId)) {
            log.info("[handleIncomingRequest][检测到FS c-leg携带X-Gateway-Id,直接出局] callId={}, gatewayId={}",
                    callId, gatewayId);
            messageForwarder.forwardToOutboundGateway(request, gatewayId);
            return;
        }

        // 快速推送到JsSIP坐席场景:
        // FS源+不携带X-Gateway-Id+被叫是已注册JsSIP坐席 → 直接forwardToWebSocketByUser
        // 设计背景: B2BUA架构下第二段INVITE(FS originate→sipproxy→坐席B)必须直接转发到坐席B的WebSocket,
        //   不能再走FS park,否则会形成死循环(FS park→sipproxy→FS park→...).
        //   FS originate的INVITE SDP是WebRTC格式(RTP/SAVPF+DTLS-SRTP+ICE),
        //   JsSIP坐席可直接处理WebRTC SDP,绕过FS park可避免FS间WebRTC SDP媒体协商失败.
        // 适用场景: 场景一(内部呼叫转坐席)、场景三(入局IVR转坐席)、场景七(自动外呼转坐席)等FS originate到JsSIP坐席的第二段呼叫
        // 注意: FS originate的目标是 sipProxyAddr(cc.sip-proxy.public-ip:port), INVITE的To头domain是公网IP,
        //       而JsSIP坐席注册时使用的domain是配置的SIP域(如1.com:1), 两者不一致.
        //       因此需要按username查询坐席记录,获取其真实domain,再用真实domain转发到WebSocket.
        if (SipProxyConstants.FREESWITCH.equals(source) && StrUtil.isBlank(gatewayId)) {
            // 按 username 查询坐席记录（原 sysAgentService.listByNameAndDomainNoTenant(toUser, null) 返回 List）
            // 改为 AgentInfoProvider.getAgent(toUser, null) 返回单个 AgentInfo
            // domain 传 null,SQL不拼接domain条件,避免公网IP与配置SIP域不匹配导致查不到
            AgentInfo agent = agentInfoProvider.getAgent(toUser, null);
            if (agent != null) {
                String agentDomain = agent.getDomain();
                // 关键: 按 Via 头端口识别发起 originate 的 FS 实例,覆盖 hash 选择的 freeSwitchNode。
                // 多 FS 实例(fs1:15560, fs2:16560)共用公网 IP,hash 选择可能选到错误的 FS,
                // 导致 200 OK 响应发到错误的 FS,originate 腿永远收不到 answer。
                javax.sip.header.ViaHeader viaHeader = (javax.sip.header.ViaHeader) request.getHeader(javax.sip.header.ViaHeader.NAME);
                if (viaHeader != null) {
                    int viaPort = viaHeader.getPort();
                    FsNodeInfo sourceFsNode = nodeManager.selectFreeSwitchNodeByViaPort(callId, viaPort);
                    if (sourceFsNode != null) {
                        sessionInfo.setFreeSwitchNode(sourceFsNode);
                        sessionManager.cacheSessionInfo(sessionInfo);
                    }
                }
                log.info("[handleIncomingRequest][检测到FS源INVITE被叫为已注册JsSIP坐席,直接推送到WebSocket] callId={}, toUser={}, agentDomain={}",
                        callId, toUser, agentDomain);
                messageForwarder.forwardToWebSocketByUser(toUser, agentDomain, request);
                return;
            }
        }

        // 默认场景: 转发到FS park,由ESL处理器走号码路由匹配→IVR流程
        // X-Gateway-Id头透传到FS,最终到ESL事件,作为IVR转接节点的网关覆盖项
        FsNodeInfo freeSwitchNode = sessionInfo.getFreeSwitchNode();
        if (freeSwitchNode == null) {
            freeSwitchNode = nodeManager.selectFreeSwitchNode(callId);
        }
        if (freeSwitchNode == null) {
            log.error("[handleIncomingRequest][没有可用的FreeSWITCH节点] callId={}", callId);
            sendErrorResponse(callId, request, Response.SERVER_INTERNAL_ERROR);
            return;
        }
        messageForwarder.forwardToFreeSwitch(request, freeSwitchNode);
        log.info("[handleIncomingRequest][INVITE已转发到FS park] callId={}, fs={}:{}",
                callId, freeSwitchNode.getSipIp(), freeSwitchNode.getSipPort());
        } finally {
            // 清理当前线程 traceId,避免线程复用导致日志串扰
            // 替代原 TraceUtil.clear()，改为可选注入的 TraceContext 清理（此处复用 setTraceId(null) 语义）
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

    /**
     * 从SIP请求头中提取X-Gateway-Id
     *
     * 需求: FS回注INVITE可能携带X-Gateway-Id自定义头域,用于标识出局呼叫的目标网关
     * 预期结果: 如果请求中存在X-Gateway-Id头域,返回其值;否则返回null
     * 处理逻辑: 通过JAIN-SIP API获取X-Gateway-Id头域,提取其值并去除前后空白
     *
     * @param request SIP请求
     * @return 网关ID,不存在时返回null
     */
    private String extractGatewayId(Request request) {
        try {
            Header gatewayIdHeader = request.getHeader("X-Gateway-Id");
            if (gatewayIdHeader != null) {
                // Header.toString() 格式为 "X-Gateway-Id: value"，需要提取冒号后的值
                String headerStr = gatewayIdHeader.toString();
                int colonIndex = headerStr.indexOf(':');
                if (colonIndex >= 0 && colonIndex < headerStr.length() - 1) {
                    return headerStr.substring(colonIndex + 1).trim();
                }
            }
        } catch (Exception e) {
            log.warn("[extractGatewayId][提取X-Gateway-Id头域异常]", e);
        }
        return null;
    }
}
