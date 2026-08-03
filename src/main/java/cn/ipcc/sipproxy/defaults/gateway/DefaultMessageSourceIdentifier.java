package cn.ipcc.sipproxy.defaults.gateway;

import cn.hutool.core.util.StrUtil;
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

import javax.sip.header.UserAgentHeader;
import javax.sip.message.Message;
import java.util.List;

/**
 * 消息来源识别默认实现
 * <p>
 * 设计意图：基于 六层递进优先级 识别 SIP 消息来源，核心目标是
 * <b>优先查询坐席记录（From 头 extension+domain）精准识别坐席来源</b>，
 * 再用 IP+端口 精确匹配已注册节点列表，避免仅靠 User-Agent 误判/漏判
 * （第三方网关也可能是 FreeSWITCH 部署；普通 SIP 坐席客户端 UA 不含 JsSIP 标识）。
 * <p>
 * 识别优先级（从高到低）：
 * <ol>
 *   <li>自定义头 X-FS-Source：自有 FS originate 时注入，最高优先级区分同 IP 下 FS 与第三方网关</li>
 *   <li><b>坐席记录匹配（From 头 extension + domain）</b>：从 From 头提取坐席分机号与域名，
 *       调用 {@link AgentInfoProvider#getAgent(String, String)} 查询，坐席记录存在 → WEBSOCKET。
 *       <b>这是主判断逻辑</b>，兼容 JsSIP/WebRTC/硬电话/软电话等任意 SIP 客户端类型的坐席，
 *       不再单一依赖 JsSIP UA 标识，避免普通 SIP 坐席被误判为 FS/网关。</li>
 *   <li>JsSIP UA 兜底识别：当坐席记录查询异常/extension 提取失败时，
 *       用 UA 包含 IPCC_JSSIP 作为兜底，兼容存量 JsSIP 客户端场景</li>
 *   <li>FS 节点 IP+端口 精确匹配：遍历 {@link FsNodeProvider#listFsNodes()}，
 *       sipIp+sipPort 与 (sourceIp,sourcePort) <b>完全一致</b> → FREESWITCH</li>
 *   <li>第三方网关 IP 匹配（忽略端口）：遍历 {@link GatewayProvider#listEnabledGateways()}，
 *       address 与 sourceIp <b>仅比较 IP</b>（忽略端口差异）→ THIRD_PARTY。
 *       为什么忽略端口？FS 型第三方网关可能有多个 profile（internal/external），
 *       originate INVITE 的源端口可能来自 external profile（如 9977），
 *       而数据库配置的是对接用的 internal profile 端口（如 9988），精确匹配会失败。
 *       IP 维度已经足够区分"自有 FS 节点"与"第三方网关节点"。</li>
 *   <li>FS UA 兜底识别：仅当 IP+端口 / IP 列表都未命中时，才识别 FreeSWITCH UA 作为兜底
 *       （用于 Response 场景下 Via received 是 sipproxy 自身 IP 的情况，
 *       此时配合 {@code UnifiedResponseHandler} 的 SessionInfo 冲突校正逻辑避免误判）</li>
 *   <li>兜底 → WEBSOCKET</li>
 * </ol>
 *
 * @author ipcc
 */
@Slf4j
public class DefaultMessageSourceIdentifier implements MessageSourceIdentifier {

    private final FsNodeProvider fsNodeProvider;
    private final GatewayProvider gatewayProvider;
    private final AgentInfoProvider agentInfoProvider;

    /**
     * 构造方法注入 FS 节点、网关、坐席查询扩展点
     *
     * @param fsNodeProvider    FS 节点查询扩展点（用于校验来源 IP 是否为 FS 节点）
     * @param gatewayProvider   网关查询扩展点（用于校验来源 IP 是否为第三方网关）
     * @param agentInfoProvider 坐席信息查询扩展点（用于校验 From 头是否为坐席）
     */
    public DefaultMessageSourceIdentifier(FsNodeProvider fsNodeProvider, GatewayProvider gatewayProvider,
                                          AgentInfoProvider agentInfoProvider) {
        this.fsNodeProvider = fsNodeProvider;
        this.gatewayProvider = gatewayProvider;
        this.agentInfoProvider = agentInfoProvider;
    }

    /**
     * 识别消息来源
     * <p>
     * 处理流程（6 层递进优先级）：
     * <ol>
     *   <li>自定义头 X-FS-Source → FREESWITCH（自有 FS originate 注入，优先级最高）</li>
     *   <li><b>坐席记录匹配（From 头 extension + domain）</b>：从 From 头提取分机号与域名，
     *       调用 {@link AgentInfoProvider#getAgent(String, String)} 查询坐席记录，
     *       坐席存在 → WEBSOCKET。<b>此为坐席判断主逻辑，兼容任意 SIP 客户端类型</b>，
     *       不再单一依赖 JsSIP UA，避免普通 SIP 软电话/硬电话坐席漏判。</li>
     *   <li>JsSIP UA 兜底识别：坐席记录查询失败 / From 头解析异常时，
     *       User-Agent 含 JsSIP 标识 → WEBSOCKET（兼容存量 JsSIP 客户端）</li>
     *   <li>从 Via 头提取 (sourceIp, sourcePort) → 遍历 FS 节点匹配 sipIp+sipPort（精确）→ FREESWITCH</li>
     *   <li>未命中 FS → 遍历已启用网关匹配 address（仅比较 IP，忽略端口差异）→ THIRD_PARTY</li>
     *   <li>IP 均未命中时，才识别 FreeSWITCH UA → FREESWITCH（兜底）</li>
     *   <li>以上均不匹配 → 返回 WEBSOCKET 兜底</li>
     * </ol>
     * <p>
     * 设计背景：
     * <ul>
     *   <li><b>坐席记录优先于 JsSIP UA</b>：坐席客户端类型多样（JsSIP/WebRTC/软电话/硬电话等），
     *       只有 JsSIP 在 UA 中带 IPCC_JSSIP 标识，其余普通 SIP 客户端 UA 无特殊标记，
     *       此时必须靠"主叫号码是否为已开通坐席"来判定，否则会把普通 SIP 坐席误判成外部来源
     *       （走到 FREESWITCH/THIRD_PARTY/兜底 WEBSOCKET 但后续路由逻辑错分方向）。</li>
     *   <li><b>domain 去端口匹配</b>：From 头的 host 可能带端口（如 sip:1001@cc.voipxt.cn:5060），
     *       而坐席表 domain 字段存的是纯域名（如 cc.voipxt.cn），查询前先剥离端口；
     *       若 extension 存在但 domain 为空，则不按 domain 过滤，兼容不分域的部署。</li>
     *   <li><b>IP 匹配优先于 FreeSWITCH UA</b>：第三方网关（SBC）也可能基于 FreeSWITCH 部署，
     *       此时 UA 同样为 {@code FreeSWITCH-mod_sofia/...}，必须先通过已注册的节点
     *       IP 列表匹配才能正确区分 FREESWITCH / THIRD_PARTY，否则会误判。</li>
     *   <li><b>自有 FS 节点要求 IP+端口精确匹配</b>：因为可能存在多 FS 共用公网 IP
     *       但端口不同的部署场景，此时靠端口区分具体节点。</li>
     *   <li><b>第三方网关仅比较 IP（忽略端口）</b>：FS 型 SBC 可能有多个 SIP profile
     *       （如 internal 9988 / external 9977），originate INVITE 的源端口取决于使用
     *       哪个 profile 发出，而数据库配置的 gateway.port 往往只是对接端口。
     *       同一 IP 既是自有 FS 节点也是第三方网关的场景不存在（部署隔离），
     *       因此第4层已排除自有 FS 后，第5层只靠 IP 就足够识别第三方网关。</li>
     * </ul>
     *
     * @param message SIP 消息（Request 或 Response）
     * @return 来源标识（WEBSOCKET / FREESWITCH / THIRD_PARTY）
     */
    @Override
    public String identifySource(Message message) {
        // 第0层：自定义头 X-FS-Source 优先识别（最高优先级）
        // 设计背景:自有 FS 与第三方网关(如 gw3)可能共用同一公网 IP(62.234.191.165),
        // 仅靠 IP/Via/UA 无法区分。自有 FS 的 originate(如 makeInternalCall)会通过
        // sip_h_X-FS-Source 通道变量在 INVITE 中添加此头,标识来源为自有 FS。
        // 第三方网关的 INVITE 不会携带此头,因此可精确区分。
        javax.sip.header.Header fsSourceHeader = message.getHeader("X-FS-Source");
        if (fsSourceHeader != null) {
            log.debug("[identifySource][检测到 X-FS-Source 头,识别为自有 FS] header={}", fsSourceHeader);
            return SipProxyConstants.FREESWITCH;
        }

        String userAgent = message.getHeader(UserAgentHeader.NAME) != null
                ? message.getHeader(UserAgentHeader.NAME).toString()
                : "";
        String userAgentUpper = userAgent.toUpperCase();

        // 第1层：坐席记录匹配（From 头 extension + domain）—— 坐席判断主逻辑
        // 兼容 JsSIP/WebRTC/软电话/硬电话 等任意客户端类型，不再依赖特定 UA 标识。
        // 典型场景: 普通 SIP 软电话(如 MicroSIP/Linphone)坐席注册后发起呼叫,
        // UA 为 "MicroSIP 3.21.3" 等,不带 JsSIP,但 From 头 user=坐席分机号,必须查到坐席记录。
        try {
            String fromUser = SipAnalysisUtil.getFromUser(message);
            String fromDomainWithPort = SipAnalysisUtil.getFromDomain(message);
            // From 头 host 可能带端口(如 cc.voipxt.cn:5060),坐席表 domain 字段存纯域名,需剥离端口
            String fromDomainHost = stripPortFromHost(fromDomainWithPort);
            if (StrUtil.isNotBlank(fromUser)) {
                AgentInfo agent = agentInfoProvider.getAgent(fromUser, fromDomainHost);
                if (agent != null) {
                    log.debug("[identifySource][命中坐席记录（From头匹配）] fromUser={}, fromDomain={}, agentId={}, tenantId={}",
                            fromUser, fromDomainHost, agent.getAgentId(), agent.getTenantId());
                    return SipProxyConstants.WEBSOCKET;
                }
            }
        } catch (Exception e) {
            // From 头解析异常(如非标准 SIP URI 格式)时记录日志,降级到 UA 判断
            log.warn("[identifySource][从From头提取坐席信息异常,降级到UA判断] msg={}", e.getMessage());
        }

        // 第2层：JsSIP UA 兜底识别（坐席记录查询异常/extension 提取失败时兼容存量 JsSIP 客户端）
        if (userAgentUpper.contains(SipProxyConstants.IPCC_JSSIP)) {
            log.debug("[identifySource][JsSIP UA兜底识别（坐席记录未命中）]");
            return SipProxyConstants.WEBSOCKET;
        }

        // 第3-4层：IP 维度匹配已注册节点列表（优先级高于 FreeSWITCH UA）
        // 第三方网关也可能是 FS 部署(UA 相同)，必须先靠网关列表 IP 匹配区分
        String sourceIp = SipAnalysisUtil.getSourceIpFromMessage(message);
        int sourcePort = SipAnalysisUtil.getSourcePortFromMessage(message);

        if (sourceIp != null && !sourceIp.isEmpty()) {
            // 第3层：匹配自有 FS 节点 (sipIp + sipPort 精确匹配)
            // 自有 FS 部署在同一台机器的不同 profile（端口不同）很常见，必须按端口区分
            List<FsNodeInfo> allFreeSwitchNodes = fsNodeProvider.listFsNodes();
            if (allFreeSwitchNodes != null) {
                for (FsNodeInfo fsNode : allFreeSwitchNodes) {
                    if (matchesNodeEndpoint(sourceIp, sourcePort, fsNode.getSipIp(), fsNode.getSipPort())) {
                        log.debug("[identifySource][命中FS节点IP+端口] sourceIp={}:{}, node={}",
                                sourceIp, sourcePort, fsNode.getName());
                        return SipProxyConstants.FREESWITCH;
                    }
                }
            }
            // 第4层：匹配已启用第三方网关（仅比较 IP，忽略端口差异）
            // 典型场景：gw3（FS 型 SBC）配置 port=9988（internal profile），
            // 但 originate INVITE 实际源端口=9977（external profile），端口完全不同。
            // 由于"第3层已排除所有自有 FS 节点"，此处同一 IP 不可能再是自有 FS，
            // 所以只比较 IP 是安全的，不会与自有 FS 节点混淆。
            List<GatewayInfo> allThirdPartyNodes = gatewayProvider.listEnabledGateways();
            if (allThirdPartyNodes != null) {
                for (GatewayInfo gateway : allThirdPartyNodes) {
                    if (sourceIp.equals(gateway.getAddress())) {
                        log.debug("[identifySource][命中网关IP（忽略端口）] sourceIp={}:{}, sourcePort忽略, gateway={}, 配置port={}",
                                sourceIp, sourcePort, gateway.getName(), gateway.getPort());
                        return SipProxyConstants.THIRD_PARTY;
                    }
                }
            }
        } else {
            log.warn("[identifySource][无法提取来源IP，跳过节点列表匹配]");
        }

        // 第5层：FS UA 兜底识别（仅当 IP 列表都未命中时才走到这里）
        // Response 场景下 Via received 是 sipproxy 自身 IP，此时只能靠 UA 兜底；
        // 若第三方网关也是 FS，此处会误判为 FREESWITCH，但会在 UnifiedResponseHandler
        // 中通过 SessionInfo.thirdPartyNode/gatewayId 做二次校正纠正为 THIRD_PARTY。
        if (userAgentUpper.contains(SipProxyConstants.FREESWITCH_USER_AGENT)) {
            log.debug("[identifySource][FreeSWITCH UA兜底识别]");
            return SipProxyConstants.FREESWITCH;
        }

        // 第6层：兜底返回 WEBSOCKET
        log.debug("[identifySource][未匹配任何节点/UA，兜底返回WEBSOCKET] sourceIp={}", sourceIp);
        return SipProxyConstants.WEBSOCKET;
    }

    /**
     * 从 host:port 格式字符串中剥离端口，返回纯 host
     * <p>
     * 设计背景：SIP URI 的 host 部分可能带端口（如 cc.voipxt.cn:5060、[::1]:5060），
     * 而坐席表 domain 字段存储的是纯域名/IP（不包含端口），查询坐席前必须统一剥离端口，
     * 否则会因 "cc.voipxt.cn:5060" != "cc.voipxt.cn" 导致坐席查询漏匹配。
     *
     * @param hostWithPort 可能包含端口的 host 字符串（允许 null/空）
     * @return 剥离端口后的纯 host；输入 null/空则原样返回
     */
    private static String stripPortFromHost(String hostWithPort) {
        if (StrUtil.isBlank(hostWithPort)) {
            return hostWithPort;
        }
        // IPv6 带端口形如 [::1]:5060，先匹配 ]: 再截取
        int bracketIdx = hostWithPort.lastIndexOf(']');
        if (bracketIdx >= 0) {
            int colonAfterBracket = hostWithPort.indexOf(':', bracketIdx + 1);
            if (colonAfterBracket >= 0) {
                return hostWithPort.substring(0, colonAfterBracket);
            }
            return hostWithPort;
        }
        // IPv4 / 域名: 最后一个冒号后是端口
        int lastColon = hostWithPort.lastIndexOf(':');
        if (lastColon >= 0) {
            return hostWithPort.substring(0, lastColon);
        }
        return hostWithPort;
    }

    /**
     * 比较 (实际来源 IP/端口) 与 (节点配置 IP/端口) 是否匹配
     * <p>
     * 比较规则：
     * <ol>
     *   <li>IP 必须完全相等（支持多 FS 共用公网 IP 但端口不同的场景）</li>
     *   <li>端口比较：节点配置了 port 时必须精确相等；节点未配置 port（null）时忽略端口比较
     *       （兼容部分 GatewayInfo.port=null 默认 5060 的场景）</li>
     * </ol>
     *
     * @param actualIp   实际来源 IP
     * @param actualPort 实际来源端口
     * @param nodeIp     节点配置 IP
     * @param nodePort   节点配置端口（允许 null）
     * @return true=匹配
     */
    private static boolean matchesNodeEndpoint(String actualIp, int actualPort, String nodeIp, Integer nodePort) {
        if (!actualIp.equals(nodeIp)) {
            return false;
        }
        if (nodePort == null) {
            return true;
        }
        return actualPort == nodePort;
    }
}
