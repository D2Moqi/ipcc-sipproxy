package cn.ipcc.sipproxy.core.node;

import cn.hutool.core.collection.CollUtil;
import cn.ipcc.sipproxy.api.fs.FsNodeProvider;
import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.support.RedisConstants;
import cn.ipcc.sipproxy.support.model.FsNodeInfo;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * SIP 节点管理器
 * <p>
 * 设计意图：替代原依赖 FsConfigService / FsSipGatewayService 的实现，通过 {@link FsNodeProvider}
 * 与 {@link GatewayProvider} 扩展点获取节点列表，sipproxy 不直接依赖父程序 ORM。
 * <p>
 * 节点选择策略保留原有逻辑：
 * <ul>
 *   <li>FreeSWITCH 节点：按 Call-ID 哈希选择，会话内缓存绑定，支持 Via 端口匹配与故障转移</li>
 *   <li>第三方节点：按来源 IP 反查网关，会话内缓存绑定</li>
 * </ul>
 */
@Slf4j
@Component
public class SipNodeManager {

    @Resource
    private FsNodeProvider fsNodeProvider;

    @Resource
    private GatewayProvider gatewayProvider;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    // ========== 会话-节点映射（Redis） ==========

    /**
     * 缓存会话-节点映射
     */
    public void cacheSessionNode(String callId, FsNodeInfo node) {
        try {
            String nodeJson = objectMapper.writeValueAsString(node);
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.SESSION_NODE_MAPPING_PREFIX + callId,
                    nodeJson,
                    RedisConstants.REFRESH_TIME,
                    TimeUnit.SECONDS);
            log.debug("[cacheSessionNode][缓存会话节点映射] callId={}, node={}", callId, node.getName());
        } catch (JsonProcessingException e) {
            log.error("[cacheSessionNode][序列化节点失败] callId={}", callId, e);
        }
    }

    /**
     * 获取会话节点映射
     */
    public FsNodeInfo getSessionNode(String callId) {
        try {
            String nodeJson = stringRedisTemplate.opsForValue().get(
                    RedisConstants.SESSION_NODE_MAPPING_PREFIX + callId);
            if (nodeJson != null) {
                return objectMapper.readValue(nodeJson, FsNodeInfo.class);
            }
        } catch (Exception e) {
            log.error("[getSessionNode][获取会话节点失败] callId={}", callId, e);
        }
        return null;
    }

    /**
     * 移除会话节点映射
     */
    public void removeSessionNode(String callId) {
        stringRedisTemplate.delete(RedisConstants.SESSION_NODE_MAPPING_PREFIX + callId);
        log.debug("[removeSessionNode][移除会话节点映射] callId={}", callId);
    }

    // ========== 会话-第三方节点映射（Redis） ==========

    /**
     * 缓存会话-第三方节点映射
     */
    public void cacheThirdPartySessionNode(String callId, FsNodeInfo node) {
        try {
            String nodeJson = objectMapper.writeValueAsString(node);
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.SESSION_THIRD_PARTY_MAPPING_PREFIX + callId,
                    nodeJson,
                    RedisConstants.REFRESH_TIME,
                    TimeUnit.SECONDS);
            log.debug("[cacheThirdPartySessionNode][缓存第三方节点映射] callId={}, node={}", callId, node.getName());
        } catch (JsonProcessingException e) {
            log.error("[cacheThirdPartySessionNode][序列化节点失败] callId={}", callId, e);
        }
    }

    /**
     * 获取会话第三方节点映射
     */
    public FsNodeInfo getThirdPartySessionNode(String callId) {
        try {
            String nodeJson = stringRedisTemplate.opsForValue().get(
                    RedisConstants.SESSION_THIRD_PARTY_MAPPING_PREFIX + callId);
            if (nodeJson != null) {
                return objectMapper.readValue(nodeJson, FsNodeInfo.class);
            }
        } catch (Exception e) {
            log.error("[getThirdPartySessionNode][获取第三方节点失败] callId={}", callId, e);
        }
        return null;
    }

    /**
     * 移除会话第三方节点映射
     */
    public void removeThirdPartySessionNode(String callId) {
        stringRedisTemplate.delete(RedisConstants.SESSION_THIRD_PARTY_MAPPING_PREFIX + callId);
        log.debug("[removeThirdPartySessionNode][移除第三方节点映射] callId={}", callId);
    }

    // ========== 第三方节点列表 ==========

    /**
     * 将 GatewayInfo 转换为 FsNodeInfo
     * <p>
     * 业务背景：原 cc-server 中 getAllThirdPartyNodes 通过 FsSipGatewayService.getListByExternalCache
     * 获取 FsSipGatewayDO 列表，再 convertToFsConfig 转为 FsConfigDO。迁移后通过 GatewayProvider.listGateways()
     * 获取 GatewayInfo 列表，转为 FsNodeInfo 以保持原有 SessionInfo.thirdPartyNode 类型契约。
     */
    private FsNodeInfo convertToFsNode(GatewayInfo gateway) {
        FsNodeInfo node = new FsNodeInfo();
        // GatewayInfo.id 为 String 形式，FsNodeInfo.id 为 Long；解析失败时不影响核心信令转发
        try {
            node.setId(gateway.getId() != null ? Long.valueOf(gateway.getId()) : null);
        } catch (NumberFormatException e) {
            log.warn("[convertToFsNode][网关ID非数字格式,忽略] gatewayId={}", gateway.getId());
        }
        node.setName(gateway.getName());
        // 解析 proxy 字段获取 IP 和端口，默认端口 5060
        String proxy = gateway.getProxy();
        if (proxy != null) {
            String[] parts = proxy.split(":");
            node.setSipIp(parts[0]);
            node.setSipPort(parts.length > 1 ? Integer.parseInt(parts[1]) : 5060);
        }
        // 状态默认 1（在线），原 FsConfigDO convertToFsConfig 设置 status=0，
        // 但 GatewayInfo 通常仅返回在线网关，这里设为 1 表示可选用
        node.setStatus(1);
        return node;
    }

    /**
     * 获取所有第三方节点
     */
    public List<FsNodeInfo> getAllThirdPartyNodes() {
        try {
            // 通过 GatewayProvider 扩展点获取外部网关列表（原 fsSipGatewayService.getListByExternalCache）
            List<GatewayInfo> gatewayList = gatewayProvider.listGateways();
            if (CollUtil.isEmpty(gatewayList)) {
                return Collections.emptyList();
            }
            // 将 GatewayInfo 转换为 FsNodeInfo
            return gatewayList.stream()
                    .map(this::convertToFsNode)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[getAllThirdPartyNodes][获取第三方节点列表失败]", e);
        }
        return Collections.emptyList();
    }

    // ========== FreeSWITCH 节点选择 ==========

    public FsNodeInfo selectFreeSwitchNode(String callId) {
        List<FsNodeInfo> onlineNodes = getOnlineFsNodes();

        if (onlineNodes == null || onlineNodes.isEmpty()) {
            log.error("[selectFreeSwitchNode][没有可用的FreeSWITCH节点] callId={}", callId);
            return null;
        }

        FsNodeInfo cachedNode = getSessionNode(callId);
        if (cachedNode != null) {
            if (isNodeOnline(cachedNode, onlineNodes)) {
                return cachedNode;
            }
            removeSessionNode(callId);
        }

        int hashCode = callId.hashCode();
        int nodeIndex = Math.abs(hashCode) % onlineNodes.size();
        FsNodeInfo selectedNode = onlineNodes.get(nodeIndex);

        cacheSessionNode(callId, selectedNode);

        log.debug("[selectFreeSwitchNode][选择FreeSWITCH节点] callId={}, node={}:{}",
                callId, selectedNode.getSipIp(), selectedNode.getSipPort());

        return selectedNode;
    }

    /**
     * 按 SIP Via 头来源端口匹配 FreeSWITCH 节点
     *
     * <p>需求背景：多 FS 实例部署时(如 fs1:15560, fs2:16560, 共用公网 IP),
     * FS originate 到 JsSIP 坐席的 INVITE 到达 sipproxy 后,需将 200 OK 等响应回送到
     * <b>发起 originate 的那个 FS 实例</b>,而非 hash 随机选中的 FS。
     * 若选错 FS,响应发到错误的 FS 实例,originate 腿永远收不到 answer,导致呼叫失败。</p>
     *
     * <p>处理逻辑：
     * 1. 获取在线 FS 节点列表
     * 2. 按 Via 头端口(即 FS 的 SIP 监听端口)匹配 FsNodeInfo.sipPort
     * 3. 匹配成功则缓存并返回该节点;失败则 fallback 到 hash 选择</p>
     *
     * @param callId  Call-ID,用于会话缓存
     * @param viaPort Via 头端口(FS SIP 监听端口),为 -1 时 fallback
     * @return 匹配到的 FS 节点,无匹配时返回 hash 选择的节点
     */
    public FsNodeInfo selectFreeSwitchNodeByViaPort(String callId, int viaPort) {
        List<FsNodeInfo> onlineNodes = getOnlineFsNodes();
        if (onlineNodes == null || onlineNodes.isEmpty()) {
            log.error("[selectFreeSwitchNodeByViaPort][没有可用的FreeSWITCH节点] callId={}", callId);
            return null;
        }
        // 优先按 Via 头端口匹配 FS SIP 监听端口(fs1/fs2 公网IP相同但端口不同)
        // 注意: 必须先匹配再查缓存,因为调用方可能在此之前已通过 selectFreeSwitchNode(callId)
        // 做了 hash 选择并缓存了节点,若先返回缓存会跳过 Via 端口匹配,
        // 导致 200 OK 响应被转发到错误的 FS 实例,b-leg 通道收不到 answer
        if (viaPort > 0) {
            for (FsNodeInfo node : onlineNodes) {
                if (node.getSipPort() != null && node.getSipPort() == viaPort) {
                    cacheSessionNode(callId, node);
                    log.info("[selectFreeSwitchNodeByViaPort][按Via端口匹配到来源FS] callId={}, viaPort={}, node={}",
                            callId, viaPort, node.getName());
                    return node;
                }
            }
            log.warn("[selectFreeSwitchNodeByViaPort][Via端口未匹配到FS] callId={}, viaPort={}", callId, viaPort);
        }
        // Via 匹配失败时 fallback: 先返回已缓存节点,再 hash 选择
        FsNodeInfo cachedNode = getSessionNode(callId);
        if (cachedNode != null && isNodeOnline(cachedNode, onlineNodes)) {
            return cachedNode;
        }
        return selectFreeSwitchNode(callId);
    }

    private List<FsNodeInfo> getOnlineFsNodes() {
        try {
            // 通过 FsNodeProvider 扩展点获取在线 FS 节点列表（原 fsConfigService.getOnlineFsNodesFromCache）
            return fsNodeProvider.listFsNodes();
        } catch (Exception e) {
            log.error("[getOnlineFsNodes][获取FreeSWITCH节点失败]", e);
            return Collections.emptyList();
        }
    }

    private boolean isNodeOnline(FsNodeInfo node, List<FsNodeInfo> onlineNodes) {
        return onlineNodes.stream()
                .anyMatch(n -> n.getId() != null && n.getId().equals(node.getId()));
    }

    /**
     * 选择第三方SIP节点
     *
     * <p>业务背景：多个第三方网关（如运营商中继）通过 SIP INVITE 接入 sipproxy 时，
     * 需要按 INVITE 来源 IP 反查匹配的网关节点，以区分不同第三方网关，确保后续响应能正确回送到来源网关。</p>
     *
     * <p>处理逻辑：
     * 1. 优先返回已缓存的会话第三方节点（同一会话多次调用保持一致）
     * 2. 遍历所有第三方节点，按 sourceIp 与节点 IP 匹配
     * 3. 节点 IP 可能存储为 "ip:port" 格式，匹配前需分割取 IP 部分
     * 4. 匹配成功返回该节点；全部不匹配时 fallback 到第一个节点并打印告警日志</p>
     *
     * @param callId   Call-ID，用于日志关联与会话缓存，可为 null
     * @param sourceIp INVITE 来源 IP（不含端口，由 Via 头解析得到），可为 null
     * @return 匹配到的第三方节点；节点列表为空时返回 null
     */
    public FsNodeInfo selectThirdPartyNode(String callId, String sourceIp) {
        // 优先返回已缓存节点，保证同一会话多次调用选择同一第三方网关
        if (callId != null) {
            FsNodeInfo cachedNode = getThirdPartySessionNode(callId);
            if (cachedNode != null) {
                return cachedNode;
            }
        }

        List<FsNodeInfo> allNodes = getAllThirdPartyNodes();
        if (allNodes.isEmpty()) {
            log.warn("[selectThirdPartyNode][第三方节点列表为空] callId={}, sourceIp={}", callId, sourceIp);
            return null;
        }

        FsNodeInfo selectedNode = null;
        // 按 sourceIp 反查匹配的网关节点，区分多个第三方网关入呼
        if (sourceIp != null) {
            for (FsNodeInfo node : allNodes) {
                String nodeIp = extractIp(node.getSipIp());
                if (sourceIp.equals(nodeIp)) {
                    selectedNode = node;
                    break;
                }
            }
        }

        // 匹配失败 fallback 到第一个节点，保证入呼流程不中断
        if (selectedNode == null) {
            selectedNode = allNodes.get(0);
            log.warn("第三方节点按来源IP匹配失败，fallback到第一个节点 callId={}, sourceIp={}", callId, sourceIp);
        }

        if (callId != null) {
            cacheThirdPartySessionNode(callId, selectedNode);
            log.debug("[selectThirdPartyNode][Call-ID: {} 选择第三方SIP节点: {}:{}]",
                    callId, selectedNode.getSipIp(), selectedNode.getSipPort());
        }

        return selectedNode;
    }

    /**
     * 从节点 IP 字段中提取纯 IP（去掉端口部分）
     *
     * <p>节点 IP 可能存储为 "ip" 或 "ip:port" 格式，匹配前需统一为纯 IP。
     * 兼容 IPv4 与 IPv6：
     * - IPv6 方括号格式 "[::1]:5060" 取方括号内 IP
     * - IPv4 "ip:port" 格式（仅含一个冒号）取冒号前部分
     * - 纯 IPv6（含多个冒号，无端口）整体返回</p>
     *
     * @param ipField 节点 IP 字段值，可为 null
     * @return 纯 IP 地址；入参为 null 时返回 null
     */
    private String extractIp(String ipField) {
        if (ipField == null) {
            return null;
        }
        String trimmed = ipField.trim();
        // IPv6 方括号格式 [::1]:5060 → 取方括号内 IP
        if (trimmed.startsWith("[")) {
            int endBracket = trimmed.indexOf(']');
            if (endBracket > 0) {
                return trimmed.substring(1, endBracket);
            }
        }
        // IPv4 "ip:port" 格式（仅含一个冒号）取冒号前部分；纯 IPv6（含多个冒号）整体返回
        int firstColon = trimmed.indexOf(':');
        int lastColon = trimmed.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return trimmed.substring(0, firstColon);
        }
        return trimmed;
    }

    public FsNodeInfo selectAlternativeFreeSwitchNode(List<FsNodeInfo> triedNodes, String callId) {
        log.info("[selectAlternativeFreeSwitchNode][开始选择备用节点] triedCount={}, callId={}",
                triedNodes.size(), callId);

        List<FsNodeInfo> onlineNodes = getOnlineFsNodes();

        if (onlineNodes == null || onlineNodes.isEmpty()) {
            log.error("[selectAlternativeFreeSwitchNode][没有可用的节点]");
            return null;
        }

        FsNodeInfo alternativeNode = null;
        for (FsNodeInfo node : onlineNodes) {
            boolean alreadyTried = triedNodes.stream()
                    .anyMatch(triedNode -> node.getId() != null && node.getId().equals(triedNode.getId()));
            if (!alreadyTried) {
                alternativeNode = node;
                break;
            }
        }

        if (alternativeNode == null) {
            log.error("[selectAlternativeFreeSwitchNode][所有节点均已尝试过]");
            return null;
        }

        if (callId != null) {
            cacheSessionNode(callId, alternativeNode);
        }

        log.info("[selectAlternativeFreeSwitchNode][已选择备用节点] alternative={}:{}",
                alternativeNode.getSipIp(), alternativeNode.getSipPort());

        return alternativeNode;
    }

    public List<FsNodeInfo> getAllFreeSwitchNodes() {
        return getOnlineFsNodes();
    }
}
