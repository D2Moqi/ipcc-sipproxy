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
    public void cacheThirdPartySessionNode(String callId, GatewayInfo node) {
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
    public GatewayInfo getThirdPartySessionNode(String callId) {
        try {
            String nodeJson = stringRedisTemplate.opsForValue().get(
                    RedisConstants.SESSION_THIRD_PARTY_MAPPING_PREFIX + callId);
            if (nodeJson != null) {
                return objectMapper.readValue(nodeJson, GatewayInfo.class);
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
     * 获取所有第三方节点
     */
    public List<GatewayInfo> getAllThirdPartyNodes() {
        try {
            // 通过 GatewayProvider 扩展点获取外部网关列表
            List<GatewayInfo> gatewayList = gatewayProvider.listEnabledGateways();
            if (CollUtil.isEmpty(gatewayList)) {
                return Collections.emptyList();
            }
            // 将 GatewayInfo 转换为 FsNodeInfo
            return gatewayList;
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
     * 2. 遍历所有第三方节点，按 sourceIp 与节点 address 精确匹配
     * 3. 匹配成功返回该节点；全部不匹配时返回 null，不做 fallback（避免错误路由）</p>
     *
     * @param callId   Call-ID，用于日志关联与会话缓存，可为 null
     * @param sourceIp INVITE 来源 IP（不含端口，由 Via 头解析得到），可为 null
     * @return 匹配到的第三方节点；无匹配或节点列表为空时返回 null
     */
    public GatewayInfo selectThirdPartyNode(String callId, String sourceIp) {
        if (callId != null) {
            GatewayInfo cachedNode = getThirdPartySessionNode(callId);
            if (cachedNode != null) {
                return cachedNode;
            }
        }

        List<GatewayInfo> allNodes = getAllThirdPartyNodes();
        if (allNodes.isEmpty()) {
            log.warn("[selectThirdPartyNode][第三方节点列表为空] callId={}, sourceIp={}", callId, sourceIp);
            return null;
        }

        GatewayInfo selectedNode = null;
        if (sourceIp != null) {
            for (GatewayInfo node : allNodes) {
                if (sourceIp.equals(node.getAddress())) {
                    selectedNode = node;
                    break;
                }
            }
        }

        if (selectedNode == null) {
            log.warn("[selectThirdPartyNode][来源IP未匹配任何网关，返回null] callId={}, sourceIp={}", callId, sourceIp);
            return null;
        }

        if (callId != null) {
            cacheThirdPartySessionNode(callId, selectedNode);
            log.debug("[selectThirdPartyNode][选择第三方SIP节点] callId={}, node={}:{}",
                    callId, selectedNode.getAddress(), selectedNode.getPort());
        }

        return selectedNode;
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
}
