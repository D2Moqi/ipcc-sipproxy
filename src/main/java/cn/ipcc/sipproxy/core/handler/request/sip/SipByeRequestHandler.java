package cn.ipcc.sipproxy.core.handler.request.sip;

import cn.hutool.core.text.CharPool;
import cn.hutool.core.util.StrUtil;
import cn.ipcc.sipproxy.api.agent.AgentInfoProvider;
import cn.ipcc.sipproxy.core.annotation.SipMethod;
import cn.ipcc.sipproxy.core.handler.response.ResponseForwardingStrategy;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.model.AgentInfo;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * 传入BYE请求处理器
 * 处理来自FreeSWITCH/第三方SIP的BYE请求
 *
 * 需求: 任一端挂断时, SIP代理作为B2BUA需将BYE转发到对话另一端, 释放媒体与UI状态
 * 预期结果: BYE按会话决策目标转发: 主叫(第三方)挂断→FreeSWITCH, FS挂断→坐席WebSocket/主叫
 * 处理逻辑:
 *   1. 提取Call-ID, 校验To头完整性
 *   2. 按 Call-ID 查 SessionInfo: 会话存在则复用 ResponseForwardingStrategy
 *      按 source + callType 决策转发目标(与 SipDefaultRequestHandler 一致):
 *        - THIRD_PARTY 来源(主叫/第三方网关挂断) → FreeSWITCH(挂断FS侧leg)
 *        - FREESWITCH 来源(FS挂断) → 内部/外呼→坐席WebSocket, 呼入→第三方主叫
 *        - WEBSOCKET 来源(坐席挂断) → FreeSWITCH(坐席主动挂断经WsByeRequestHandler,此处兜底)
 *   3. 会话不存在: fallback 到按To头注册状态转发(向后兼容)
 *
 * 主叫(pjsua)挂断时 BYE 的 To=被叫IVR号码(4001234, 非坐席注册用户): 若按 To 头注册状态转发,
 * 200 OK 中 Contact 改为代理地址后 BYE Request-URI 指向代理, To=4001234 未注册 → 走"转发第三方"
 * 分支且网关匹配失败 → BYE 丢弃, FS 侧 leg 永不挂断, 只能靠媒体超时(10s+/30s)兜底,
 * 坐席侧挂断联动延迟严重。改为按 source+callType 会话决策后, 主叫挂断的 BYE 正确转发到 FreeSWITCH。
 *
 * B2BUA两段BYE协调说明:
 *   - 本处理器负责"FS↔第三方/网关"段BYE转发(对端挂断通知另一端的场景)
 *   - "坐席→FS"段BYE(坐席主动挂断)由WsByeRequestHandler处理
 *   - 两段BYE相互独立, FS与坐席端各自处理媒体释放
 *   - CallInfo/会话信息的清理由CHANNEL_HANGUP_COMPLETE事件处理器统一完成,BYE处理器不参与清理
 *
 * @author ipcc
 */
@Slf4j
@Component
@SipMethod(SipAnalysisUtil.BYE)
public class SipByeRequestHandler extends AbstractSipRequestHandler {

    @Resource
    private ResponseForwardingStrategy responseForwardingStrategy;

    /**
     * 坐席信息查询扩展点（用于 BYE 方向校正：判断 From/To 用户是否为坐席分机）
     */
    @Resource
    private AgentInfoProvider agentInfoProvider;

    /**
     * 处理传入BYE请求
     *
     * 需求: 任一端挂断时, B2BUA 将 BYE 转发到对话另一端
     * 预期结果: BYE 按会话决策(source+callType)转发到正确目标
     * 处理逻辑: 校验To头 → 按 Call-ID 查会话 → 会话存在按决策转发, 不存在按注册转发
     * 异常场景: To头信息不完整时回送BAD_REQUEST响应; 转发失败由底层抛出异常
     * 前置条件: 调用方已从请求中提取callId并判定消息来源source
     *
     * @param request SIP BYE请求
     * @param callId  Call-ID(由调用方提取, 非空)
     * @param source  消息来源(FREESWITCH/THIRD_PARTY/WEBSOCKET), 用于决策转发目标
     * @throws Exception To头校验失败时回送错误响应, 转发失败时抛出异常
     */
    @Override
    public void handle(Request request, String callId, String source) throws Exception {
        log.info("[handleIncomingRequest][处理传入BYE请求] callId={}, source={}", callId, source);

        // Call-ID 缺失时无法按会话决策, 直接 fallback 到按注册转发
        if (callId == null || callId.isEmpty()) {
            log.warn("[handleIncomingRequest][Call-ID为空, fallback到按注册转发]");
            fallbackForwardByRegistration(request, callId);
            return;
        }

        String toUser = SipAnalysisUtil.extractToUser(request);
        String toDomain = SipAnalysisUtil.extractToDomain(request);

        if (!validateToHeader(toUser, toDomain)) {
            log.warn("[handleIncomingRequest][BYE To头不完整,回送错误响应] callId={}", callId);
            sendErrorResponse(callId, request, Response.BAD_REQUEST);
            return;
        }

        // 按 Call-ID 查会话信息
        SessionInfo sessionInfo = sessionManager.getSessionInfo(callId);
        if (sessionInfo == null) {
            // 会话不存在(未完整走 INVITE 处理器), fallback 到按 To 头查注册转发, 保证向后兼容
            log.warn("[handleIncomingRequest][SessionInfo不存在, fallback到按注册转发] callId={}", callId);
            forwardRequestByRegistration(request, callId, toUser, toDomain);
            return;
        }

        // 会话已建立, 刷新会话信息(保持会话活跃, 避免被淘汰清理)
        sessionManager.updateSessionInfo(sessionInfo);

        // 方案A(2026-08-15): 会话内 BYE 按 SessionInfo 上下文校正来源(而非仅靠 Via IP 端口匹配)
        // 根因: 内部呼叫(A-B坐席互拨)中 FS 挂断坐席B 腿时, 从 FS internal profile(如 15560)发出的
        // BYE 源端口不匹配 FS 节点表 external 端口(如 15580), 且源IP(62.234.191.165)命中第三方网关
        // (fs3 同公网IP) → identifySource 误判 THIRD_PARTY → 决策 (THIRD_PARTY,INTERNAL)→FREESWITCH
        // 把 BYE 错误转回 FS → FS 回 481 → 坐席B 收不到正常挂断通知(场景5 保持降级链一环)。
        // 校正: INTERNAL 呼叫无第三方参与, THIRD_PARTY 必为误判; 且 BYE To 用户为坐席分机(挂断坐席端)
        // → 校正为 FREESWITCH → 决策 (FREESWITCH,INTERNAL)→WEBSOCKET 正确转发坐席B。
        String correctedSource = correctSourceBySession(request, source, sessionInfo);
        if (!correctedSource.equals(source)) {
            log.info("[handleIncomingRequest][会话内BYE来源校正] callId={}, {} → {}", callId, source, correctedSource);
        }
        source = correctedSource;

        log.info("[handleIncomingRequest][会话已建立, 按source+callType决策转发] callId={}, source={}, callType={}",
                callId, source, sessionInfo.getCallType());

        // 复用 ResponseForwardingStrategy 按 source + callType 决策转发目标
        String target = responseForwardingStrategy.getForwardingTarget(source, sessionInfo.getCallType());
        log.info("[handleIncomingRequest][决策转发目标] callId={}, source={}, callType={}, target={}",
                callId, source, sessionInfo.getCallType(), target);

        // 按 target 分支转发
        forwardByTarget(request, callId, target, sessionInfo);
    }

    /**
     * 会话内 BYE 来源校正（方案A）
     * <p>
     * 设计意图：BYE 是 in-dialog 请求，已有 {@link SessionInfo} 上下文时，来源判定应结合
     * 会话上下文（呼叫类型 + From/To 是否坐席），而非仅依赖 Via 源 IP+端口匹配。
     * <p>
     * 背景（2026-08-15 场景5 保持降级深挖）：内部呼叫（坐席A⇄坐席B）中 FS 挂断坐席B 腿时，
     * FS 从 internal profile（如 fs1 的 15560 / fs2 的 16560）发出 BYE，源端口不匹配 FS 节点表
     * external 端口（15580/16580）→ 第3层 FS 节点匹配失败；且 BYE 的 From 头常为坐席A 分机
     * （FS 复用原 From）→ 第1层坐席记录匹配误判 WEBSOCKET；源 IP 命中第三方网关（fs3 同公网 IP）
     * 时又可能误判 THIRD_PARTY。无论哪种误判，决策表都会把 BYE 转回 FS 而非坐席B，
     * FS 回 481，坐席B 收不到正常挂断通知。
     * <p>
     * 校正依据：<b>本处理器只接收来自 FS/第三方（UDP/TCP 通道）的 BYE</b>——坐席的 BYE 走
     * WebSocket 通道由 {@code WsByeRequestHandler} 处理。因此凡进入本处理器且 To 用户为坐席
     * 分机的 BYE，一定是"对端（FS/第三方）挂断坐席端"，应转发坐席 WebSocket。
     * <p>
     * 校正规则（按优先级）：
     * <ol>
     *   <li><b>BYE To 用户为坐席且 From 域不匹配会话中的第三方网关</b> → FREESWITCH
     *       （FS 挂断坐席端；决策 (FREESWITCH, INTERNAL/OUTBOUND)→WEBSOCKET 正确转发坐席）</li>
     *   <li><b>BYE To 用户为坐席且 From 域匹配第三方网关</b> → THIRD_PARTY
     *       （第三方网关直连挂断主叫坐席，转 FS 挂腿；(THIRD_PARTY, OUTBOUND)→FREESWITCH）</li>
     *   <li>To 非坐席（主叫/被叫号码挂断）→ 保持原识别（THIRD_PARTY 主叫挂断 → 转 FS）</li>
     * </ol>
     *
     * @param request     SIP BYE 请求
     * @param source      调用方识别出的原始来源（WEBSOCKET/FREESWITCH/THIRD_PARTY）
     * @param sessionInfo 会话信息（提供 thirdPartyNode 等上下文）
     * @return 校正后的来源
     */
    private String correctSourceBySession(Request request, String source, SessionInfo sessionInfo) {
        if (sessionInfo == null) {
            return source;
        }
        // BYE To 用户是否为坐席分机（FS 挂断坐席端时 To 为被挂坐席）
        String toUser = SipAnalysisUtil.extractToUser(request);
        String toDomain = SipAnalysisUtil.extractToDomain(request);
        if (StrUtil.isBlank(toUser)) {
            return source;
        }
        // 兼容注册 AOR 嵌入 domain 的格式(如 To user="1002&1.com:1"):
        // 与 DefaultMessageSourceIdentifier 坐席匹配逻辑保持一致, 分机号取 & 前部分
        String[] splitUser = toUser.split(String.valueOf(CharPool.AMP));
        String toExtension = splitUser[0];
        String toDomainHost = splitUser.length > 1 ? splitUser[1] : toDomain;
        AgentInfo toAgent = null;
        try {
            toAgent = agentInfoProvider.getAgent(toExtension, toDomainHost);
        } catch (Exception e) {
            log.warn("[correctSourceBySession][查询To坐席信息异常,保持原来源] toUser={}, err={}",
                    toUser, e.getMessage());
        }
        if (toAgent == null) {
            // To 非坐席: 主叫/被叫号码挂断, 保持原识别(THIRD_PARTY 主叫挂断 → 转 FS 等)
            // 兜底: FS 从 internal profile 挂断号码端时, 源端口不匹配节点表 external 端口,
            // 且 From 域可能命中第三方网关共用公网 IP → 来源误判; From 域命中会话 FS 节点
            // IP 时校正为 FREESWITCH, 避免 BYE 被误转回 FS 自身
            String byeFromUser = SipAnalysisUtil.extractFromUser(request);
            String byeFromDomain = SipAnalysisUtil.extractFromDomain(request);
            String[] splitByeFrom = StrUtil.isNotBlank(byeFromUser)
                    ? byeFromUser.split(String.valueOf(CharPool.AMP)) : new String[0];
            String byeFromDomainHost = splitByeFrom.length > 1
                    ? splitByeFrom[1] : stripPortFromHost(byeFromDomain);
            if (sessionInfo.getFreeSwitchNode() != null
                    && StrUtil.isNotBlank(sessionInfo.getFreeSwitchNode().getSipIp())
                    && sessionInfo.getFreeSwitchNode().getSipIp().equals(byeFromDomainHost)
                    && !SipProxyConstants.FREESWITCH.equals(source)) {
                log.info("[correctSourceBySession][BYE来源校正:FS节点IP挂断号码端] callId={}, toUser={}, fromDomain={}, {} → FREESWITCH",
                        sessionInfo.getCallId(), toUser, byeFromDomainHost, source);
                return SipProxyConstants.FREESWITCH;
            }
            return source;
        }

        // To 是坐席: BYE 目标为坐席端 → 对端(FS/第三方)挂断坐席
        // 先取 From 域(兼容 AOR 嵌入与带端口), 判断是否第三方网关直连
        String fromUser = SipAnalysisUtil.extractFromUser(request);
        String fromDomain = SipAnalysisUtil.extractFromDomain(request);
        String[] splitFrom = StrUtil.isNotBlank(fromUser)
                ? fromUser.split(String.valueOf(CharPool.AMP)) : new String[0];
        String fromDomainHost = splitFrom.length > 1
                ? splitFrom[1] : stripPortFromHost(fromDomain);
        GatewayInfo thirdPartyNode = sessionInfo.getThirdPartyNode();
        if (thirdPartyNode != null && StrUtil.isNotBlank(thirdPartyNode.getAddress())
                && thirdPartyNode.getAddress().equals(fromDomainHost)) {
            // 第三方网关直连挂断主叫坐席(如场景2 被叫端经网关挂断) → 保持/校正为 THIRD_PARTY(转 FS 挂腿)
            if (!SipProxyConstants.THIRD_PARTY.equals(source)) {
                log.info("[correctSourceBySession][BYE来源校正:第三方网关挂断坐席] callId={}, toUser={}(坐席), " +
                                "fromDomain={}(匹配网关{}), {} → THIRD_PARTY",
                        sessionInfo.getCallId(), toUser, fromDomainHost, thirdPartyNode.getAddress(), source);
                return SipProxyConstants.THIRD_PARTY;
            }
            return source;
        }
        // FS 挂断坐席端(From 复用坐席分机/经 internal 端口发, 原识别 WEBSOCKET/THIRD_PARTY 均可能误判)
        if (!SipProxyConstants.FREESWITCH.equals(source)) {
            log.info("[correctSourceBySession][BYE来源校正:FS挂断坐席端] callId={}, toUser={}(坐席), " +
                            "fromDomain={}, 原识别={} → FREESWITCH(决策转坐席WebSocket)",
                    sessionInfo.getCallId(), toUser, fromDomainHost, source);
            return SipProxyConstants.FREESWITCH;
        }
        return source;
    }

    /**
     * 剥离 host:port 中的端口（与 DefaultMessageSourceIdentifier 逻辑一致）
     */
    private static String stripPortFromHost(String hostWithPort) {
        if (StrUtil.isBlank(hostWithPort)) {
            return hostWithPort;
        }
        int bracketIdx = hostWithPort.lastIndexOf(']');
        if (bracketIdx >= 0) {
            int colonAfterBracket = hostWithPort.indexOf(':', bracketIdx + 1);
            if (colonAfterBracket >= 0) {
                return hostWithPort.substring(0, colonAfterBracket);
            }
            return hostWithPort;
        }
        int lastColon = hostWithPort.lastIndexOf(':');
        if (lastColon >= 0) {
            return hostWithPort.substring(0, lastColon);
        }
        return hostWithPort;
    }

    /**
     * 按决策目标分支转发会话内BYE请求
     *
     * @param request     SIP请求
     * @param callId      Call-ID
     * @param target      决策目标（WEBSOCKET/FREESWITCH/THIRD_PARTY）
     * @param sessionInfo 会话信息（提供 sessionId/freeSwitchNode/thirdPartyNode）
     * @throws Exception 转发失败时抛出
     */
    private void forwardByTarget(Request request, String callId, String target, SessionInfo sessionInfo) throws Exception {
        try {
            switch (target) {
                case SipProxyConstants.WEBSOCKET:
                    forwardToWebSocket(request, sessionInfo);
                    break;
                case SipProxyConstants.FREESWITCH:
                    forwardToFreeSwitch(request, sessionInfo);
                    break;
                case SipProxyConstants.THIRD_PARTY:
                    forwardToThirdParty(request, sessionInfo);
                    break;
                default:
                    // 未知目标, 记录告警并 fallback 到按注册转发兜底
                    log.warn("[forwardByTarget][未知转发目标, fallback到按注册转发] target={}, callId={}", target, callId);
                    String toUser = SipAnalysisUtil.extractToUser(request);
                    String toDomain = SipAnalysisUtil.extractToDomain(request);
                    forwardRequestByRegistration(request, callId, toUser, toDomain);
            }
        } catch (Exception e) {
            log.error("[forwardByTarget][转发失败] target={}, callId={}", target, callId, e);
            throw e;
        }
    }

    /**
     * 转发BYE到WebSocket（坐席端）
     *
     * @param request     SIP请求
     * @param sessionInfo 会话信息
     * @throws Exception 转发失败时抛出
     */
    private void forwardToWebSocket(Request request, SessionInfo sessionInfo) throws Exception {
        String sessionId = sessionInfo.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("[forwardToWebSocket][WebSocket会话ID不存在, 跳过转发] callId={}", sessionInfo.getCallId());
            return;
        }
        // 改写 WebSocket 代理头（Contact/Via/Request-URI）
        Message modifiedRequest = messageForwarder.modifyWsProxyHeaders(request);
        messageForwarder.toWebSocket(sessionId, modifiedRequest);
        log.info("[forwardToWebSocket][已转发到WebSocket] callId={}, sessionId={}",
                sessionInfo.getCallId(), sessionId);
    }

    /**
     * 转发BYE到FreeSWITCH
     *
     * @param request     SIP请求
     * @param sessionInfo 会话信息
     * @throws Exception 转发失败时抛出
     */
    private void forwardToFreeSwitch(Request request, SessionInfo sessionInfo) throws Exception {
        if (sessionInfo.getFreeSwitchNode() == null) {
            log.warn("[forwardToFreeSwitch][FreeSWITCH节点不存在, 跳过转发] callId={}", sessionInfo.getCallId());
            return;
        }
        messageForwarder.forwardToFreeSwitch(request, sessionInfo.getFreeSwitchNode());
        log.info("[forwardToFreeSwitch][已转发到FreeSWITCH] callId={}, node={}:{}",
                sessionInfo.getCallId(),
                sessionInfo.getFreeSwitchNode().getSipIp(),
                sessionInfo.getFreeSwitchNode().getSipPort());
    }

    /**
     * 转发BYE到第三方SIP服务
     *
     * @param request     SIP请求
     * @param sessionInfo 会话信息
     * @throws Exception 转发失败时抛出
     */
    private void forwardToThirdParty(Request request, SessionInfo sessionInfo) throws Exception {
        if (sessionInfo.getThirdPartyNode() == null) {
            log.warn("[forwardToThirdParty][第三方SIP节点不存在, 跳过转发] callId={}", sessionInfo.getCallId());
            return;
        }
        messageForwarder.forwardToThirdParty(request, sessionInfo.getThirdPartyNode());
        log.info("[forwardToThirdParty][已转发到第三方SIP服务] callId={}, node={}:{}",
                sessionInfo.getCallId(),
                sessionInfo.getThirdPartyNode().getAddress(),
                sessionInfo.getThirdPartyNode().getPort());
    }

    /**
     * Call-ID缺失时的fallback转发
     * 直接按To头查注册转发, 保证异常入参下不丢失信令
     *
     * @param request SIP请求
     * @param callId  Call-ID（可能为null）
     * @throws Exception 转发失败或构造错误响应失败时抛出
     */
    private void fallbackForwardByRegistration(Request request, String callId) throws Exception {
        String toUser = SipAnalysisUtil.extractToUser(request);
        String toDomain = SipAnalysisUtil.extractToDomain(request);
        if (!validateToHeader(toUser, toDomain)) {
            sendErrorResponse(callId, request, Response.BAD_REQUEST);
            return;
        }
        forwardRequestByRegistration(request, callId, toUser, toDomain);
    }
}
