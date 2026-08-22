package cn.ipcc.sipproxy.core.handler.response;

import cn.ipcc.sipproxy.api.gateway.MessageSourceIdentifier;
import cn.ipcc.sipproxy.core.forwarder.SipMessageForwarder;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.header.ViaHeader;
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
        // 1.5 Via transport 为 WS/WSS 时,来源一定是 WebSocket,跳过 SessionInfo 校正
        //     需求背景: 被叫腿(1002→sipproxy)的 200 OK Via transport=WS,identifySource 正确返回 WEBSOCKET,
        //     但 correctSourceBySessionContext 的"校正2"会因 hasFreeSwitch=true 将 WEBSOCKET 错误校正为 FREESWITCH,
        //     导致 200 OK 被错误转发回 WebSocket 而非 FS,FS 收不到 200 OK → 不发 ACK → JsSIP No ACK 超时挂断。
        //     处理: Via transport=WS/WSS 是可靠来源标识,不参与 SessionInfo 校正。
        String viaTransport = extractTopViaTransport(response);
        if ("WS".equalsIgnoreCase(viaTransport) || "WSS".equalsIgnoreCase(viaTransport)) {
            String callType = sessionInfo.getCallType();
            String target = forwardingStrategy.getForwardingTarget(SipProxyConstants.WEBSOCKET, callType);
            log.info("[determineResponseTarget][Via transport={}, source=WEBSOCKET(确定,跳过校正), callType={}, target={}, callId={}]",
                    viaTransport, callType, target, sessionInfo.getCallId());
            return target;
        }

        // 2. 来源识别+SessionInfo 上下文校正(与 407 拦截判断共用 resolveSource,保证两处来源结果一致)
        String source = resolveSource(response, sessionInfo);

        String callType = sessionInfo.getCallType();
        String target = forwardingStrategy.getForwardingTarget(source, callType);
        log.debug("[determineResponseTarget][source={}, callType={}, target={}, callId={}]",
                source, callType, target, sessionInfo.getCallId());

        return target;
    }

    /**
     * 识别响应来源并叠加 SessionInfo 上下文校正
     * <p>
     * 覆盖基类默认实现: 先委托 MessageSourceIdentifier 扩展点识别(WEBSOCKET/FREESWITCH/THIRD_PARTY),
     * 再经 correctSourceBySessionContext 校正。基类 handle() 的 407 拦截判断也调用本钩子,
     * 确保出局网关 407 挑战在来源被校正为 THIRD_PARTY 后能进入 Digest 重发链路。
     * <p>
     * Response 场景下 Via received 参数是 sipproxy 自身 IP,识别结果可能存在偏差,
     * 特别是第三方网关也是 FreeSWITCH 部署时(UA 相同),会被误识别为 FREESWITCH,
     * 需依赖 SessionInfo 上下文校正纠偏。
     */
    @Override
    protected String resolveSource(Response response, SessionInfo sessionInfo) {
        String source = messageSourceIdentifier.identifySource(response);
        // SessionInfo 上下文冲突校正（优先级高于 identifySource）
        //    场景: sipproxy 发起 INVITE 时已将目标节点存入 SessionInfo,
        //    因此 Response 的真实来源可以反向推断——"刚刚发给了谁,Response 就来自谁"。
        //    这是 Response 来源识别最可靠的依据,优于 UA/Via 等间接特征。
        return correctSourceBySessionContext(source, sessionInfo, sessionInfo.getCallType());
    }

    /**
     * 提取 Response 顶层 Via 头的 transport 字段
     * <p>
     * Via 头格式: SIP/2.0/TRANSPORT host:port;params
     * transport 为 WS/WSS 时表示消息来自 WebSocket,为 UDP/TCP 时表示来自 FS 或第三方 SIP
     *
     * @param response SIP 响应
     * @return transport 字符串(大写),提取失败返回 null
     */
    private String extractTopViaTransport(Response response) {
        try {
            ViaHeader viaHeader = (ViaHeader) response.getHeader(ViaHeader.NAME);
            return viaHeader != null ? viaHeader.getTransport() : null;
        } catch (Exception e) {
            log.warn("[extractTopViaTransport][提取Via transport失败] callId={}", safeGetCallId(response));
            return null;
        }
    }

    /**
     * 安全提取 Call-ID(内部工具方法,避免直接依赖 SipAnalysisUtil 的异常传播)
     */
    private String safeGetCallId(Response response) {
        try {
            return cn.ipcc.sipproxy.core.utils.SipAnalysisUtil.getCallId(response);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 基于 SessionInfo 上下文对 Response 来源做二次校正
     * <p>
     * 校正规则（仅当 SessionInfo 中已缓存明确的节点信息时生效，不做覆盖式校正，
     * 只处理与上下文明显冲突的情况）：
     * <ol>
     *   <li>第三方 leg(仅 OUTBOUND): {@code sessionInfo.thirdPartyNode != null} 或
     *       {@code sessionInfo.gatewayId != null} → 强制来源 = THIRD_PARTY
     *       （解决第三方网关也是 FS 部署时 UA 相同导致误识别为 FREESWITCH 的场景，
     *       典型如场景4/5/6/7的 c-leg 出局到 gw3（FS 型 SBC）时返回的 100/180/200）。
     *       <b>注意: 该校正仅限 callType=OUTBOUND</b>——呼出时 200 来自第三方网关需转给 FS;
     *       呼入(INBOUND)时 INVITE 由第三方发来转给 FS, 200 来自 FS 应回给第三方,
     *       正确映射为 (FREESWITCH, INBOUND)→THIRD_PARTY(见 ResponseForwardingStrategy),
     *       若此处无条件强制 THIRD_PARTY 会被误映射为 (THIRD_PARTY, INBOUND)→FREESWITCH 形成回环,
     *       导致 200 OK 发回 FS 自己、第三方主叫收不到 200 持续重传直至 408 超时</li>
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
     * @param callType    呼叫类型（INTERNAL/OUTBOUND/INBOUND，第三方leg校正仅 OUTBOUND 生效）
     * @return 校正后的来源标识
     */
    private String correctSourceBySessionContext(String source, SessionInfo sessionInfo, String callType) {
        boolean hasThirdParty = sessionInfo.getThirdPartyNode() != null
                || cn.hutool.core.util.StrUtil.isNotBlank(sessionInfo.getGatewayId());
        boolean hasFreeSwitch = sessionInfo.getFreeSwitchNode() != null;

        // 校正1: 明确存在第三方 leg 且为呼出(OUTBOUND) → 强制来源为 THIRD_PARTY
        // (覆盖 FS 型 SBC 返回的 100 Trying/183/200 OK 被 FS UA 兜底误识别)
        // 呼入(INBOUND)不适用: 200 来自 FS 需经 (FREESWITCH, INBOUND)→THIRD_PARTY 回给第三方主叫,
        // 强制 THIRD_PARTY 会被策略表映射回 FREESWITCH 形成回环(408 超时)
        // 坐席(WEBSOCKET)发起的外呼 INVITE 携带 X-Gateway-Id(指定网关出局)时,
        // sessionInfo.gatewayId 非空 → hasThirdParty=true, 若此时对 FS 应答 INVITE 的
        // 200 OK(初始被 UA 兜底识别为 WEBSOCKET)强制为 THIRD_PARTY → 转发回 FS,
        // 坐席收不到 200 OK → 不发 ACK → fs1 等 ACK 26s(answer 卡) → CHANNEL_ANSWER
        // 不触发 → 流程不驱动 → 无 CDR。故初始来源为 WEBSOCKET 的响应不强制
        // THIRD_PARTY, 交由校正2 识别为 FREESWITCH(FS 应答坐席 INVITE),
        // 再按策略表 (FREESWITCH, OUTBOUND)→WEBSOCKET 回给坐席。
        if (hasThirdParty && SipProxyConstants.CALL_TYPE_OUTBOUND.equals(callType)
                && !SipProxyConstants.WEBSOCKET.equals(source)) {
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
            // 原生 SIP 终端(TCP/UDP 直连,非坐席)入局 INVITE 的来源 IP 未匹配网关列表时,
            // thirdPartyNode 为 null,但响应仍需沿入站 Via/入站连接回送——forwardToThirdParty 的
            // Response 分支依赖 inboundTopVia + 入站连接注册表,不依赖网关节点,
            // 此处不丢弃,传 null 节点继续回送
            messageForwarder.forwardToThirdParty(response, null);
            log.info("[forwardToThirdParty][无匹配第三方节点,响应按入站连接回送] callId={}", sessionInfo.getCallId());
        }
    }
}
