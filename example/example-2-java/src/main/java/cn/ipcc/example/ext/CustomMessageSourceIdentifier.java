package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.agent.AgentInfoProvider;
import cn.ipcc.sipproxy.api.fs.FsNodeProvider;
import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.api.gateway.MessageSourceIdentifier;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.model.AgentInfo;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.message.Message;
import java.util.List;

/**
 * 消息来源识别扩展点自定义实现（替代 {@code DefaultMessageSourceIdentifier}）。
 * <p>
 * 用途：自定义 SIP 消息来源识别逻辑，区分 WEBSOCKET / FREESWITCH / THIRD_PARTY 三类来源。
 * 来源识别结果用于路由分发：WEBSOCKET 来源走 WS→SIP 转发，FREESWITCH/THIRD_PARTY 来源走 SIP→WS 转发。
 * <p>
 * 数据来源：通过构造注入的三个扩展点（{@link CustomAgentInfoProvider}、{@link CustomFsNodeProvider}、
 * {@link CustomGatewayProvider}）查询硬编码数据。
 * <p>
 * 与默认实现的差异：
 * <ul>
 *   <li>默认实现 {@code DefaultMessageSourceIdentifier} 采用 6 层递进优先级识别
 *       （X-FS-Source 自定义头 → 坐席记录匹配 → JsSIP UA 兜底 → FS 节点 IP+端口精确匹配 →
 *       网关 IP 匹配（忽略端口）→ FreeSWITCH UA 兜底 → 兜底 WEBSOCKET）；</li>
 *   <li>本实现简化为 3 层识别 + 兜底 UNKNOWN：
 *     <ol>
 *       <li>From 头 extension+domain 调用 AgentInfoProvider 查坐席 → 命中返回 WEBSOCKET</li>
 *       <li>Via sourceIp 调用 FsNodeProvider 查 FS 节点（IP 匹配）→ 命中返回 FREESWITCH</li>
 *       <li>sourceIp 调用 GatewayProvider 查网关（IP 匹配）→ 命中返回 THIRD_PARTY</li>
 *       <li>均未命中返回 UNKNOWN</li>
 *     </ol>
 *   </li>
 *   <li>不包含 X-FS-Source 自定义头识别、JsSIP UA 兜底、FreeSWITCH UA 兜底等复杂分支。</li>
 * </ul>
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomMessageSourceIdentifier implements MessageSourceIdentifier {

    /** 来源未知标识（sipproxy 默认使用 WEBSOCKET 兜底，本实现返回 UNKNOWN 以区分未匹配场景） */
    private static final String UNKNOWN = "UNKNOWN";

    private final AgentInfoProvider agentInfoProvider;
    private final FsNodeProvider fsNodeProvider;
    private final GatewayProvider gatewayProvider;

    /**
     * 构造方法注入坐席、FS 节点、网关查询扩展点。
     *
     * @param agentInfoProvider 坐席信息查询扩展点（用于 From 头匹配坐席）
     * @param fsNodeProvider    FS 节点查询扩展点（用于 Via IP 匹配 FS 节点）
     * @param gatewayProvider   网关查询扩展点（用于 sourceIp 匹配第三方网关）
     */
    public CustomMessageSourceIdentifier(AgentInfoProvider agentInfoProvider,
                                         FsNodeProvider fsNodeProvider,
                                         GatewayProvider gatewayProvider) {
        this.agentInfoProvider = agentInfoProvider;
        this.fsNodeProvider = fsNodeProvider;
        this.gatewayProvider = gatewayProvider;
    }

    /**
     * 识别消息来源（简化 3 层识别 + 兜底 UNKNOWN）。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>从 From 头提取 extension+domain，调用 {@link AgentInfoProvider#getAgent} 查坐席，
     *       命中 → WEBSOCKET</li>
     *   <li>从 Via 头提取 sourceIp，遍历 {@link FsNodeProvider#listFsNodes} 匹配 sipIp，
     *       命中 → FREESWITCH</li>
     *   <li>遍历 {@link GatewayProvider#listEnabledGateways} 匹配 address（仅比较 IP），
     *       命中 → THIRD_PARTY</li>
     *   <li>均未命中 → UNKNOWN</li>
     * </ol>
     *
     * @param message SIP 消息（Request 或 Response）
     * @return 来源标识（WEBSOCKET / FREESWITCH / THIRD_PARTY / UNKNOWN）
     */
    @Override
    public String identifySource(Message message) {
        // 第1层：From 头 extension+domain 匹配坐席 → WEBSOCKET
        try {
            String fromUser = SipAnalysisUtil.getFromUser(message);
            String fromDomain = SipAnalysisUtil.getFromDomain(message);
            if (fromUser != null && !fromUser.isEmpty()) {
                AgentInfo agent = agentInfoProvider.getAgent(fromUser, fromDomain);
                if (agent != null) {
                    log.debug("[identifySource][命中坐席记录（From头匹配）] fromUser={}, fromDomain={}", fromUser, fromDomain);
                    return SipProxyConstants.WEBSOCKET;
                }
            }
        } catch (Exception e) {
            log.warn("[identifySource][从From头提取坐席信息异常，降级到IP匹配] msg={}", e.getMessage());
        }

        // 提取来源 IP（Via 头），用于第2、3层匹配
        String sourceIp = SipAnalysisUtil.getSourceIpFromMessage(message);
        if (sourceIp == null || sourceIp.isEmpty()) {
            log.warn("[identifySource][无法提取来源IP，跳过节点列表匹配]");
            return UNKNOWN;
        }

        // 第2层：Via sourceIp 匹配 FS 节点（IP 匹配）→ FREESWITCH
        List<FsNodeInfo> fsNodes = fsNodeProvider.listFsNodes();
        if (fsNodes != null) {
            for (FsNodeInfo fsNode : fsNodes) {
                if (sourceIp.equals(fsNode.getSipIp())) {
                    log.debug("[identifySource][命中FS节点IP] sourceIp={}, node={}", sourceIp, fsNode.getName());
                    return SipProxyConstants.FREESWITCH;
                }
            }
        }

        // 第3层：sourceIp 匹配已启用第三方网关（仅比较 IP，忽略端口差异）→ THIRD_PARTY
        List<GatewayInfo> gateways = gatewayProvider.listEnabledGateways();
        if (gateways != null) {
            for (GatewayInfo gateway : gateways) {
                if (sourceIp.equals(gateway.getAddress())) {
                    log.debug("[identifySource][命中网关IP（忽略端口）] sourceIp={}, gateway={}", sourceIp, gateway.getName());
                    return SipProxyConstants.THIRD_PARTY;
                }
            }
        }

        // 兜底：均未命中 → UNKNOWN
        log.debug("[identifySource][未匹配任何节点/坐席，兜底返回UNKNOWN] sourceIp={}", sourceIp);
        return UNKNOWN;
    }
}
