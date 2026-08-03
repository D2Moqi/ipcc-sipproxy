package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 网关查询扩展点自定义实现（替代 {@code DefaultGatewayProvider}）。
 * <p>
 * 用途：为 sipproxy 提供第三方 SIP 网关查询能力，用于出局 INVITE 路由选择与入呼来源 IP 反查。
 * <p>
 * 数据来源：硬编码单条网关（不依赖数据库），用于演示集成与本地联调。
 * <ul>
 *   <li>id=1, name=test-gw</li>
 *   <li>address=127.0.0.1, port=5080（出局 INVITE 的目标地址）</li>
 *   <li>externalLineNumber=10086（出局 From 头/DID 改写值）</li>
 *   <li>fromDomain=sipproxy.example（出局 From 头域名）</li>
 *   <li>callerIdInFrom=1（使用 externalLineNumber 作为 From 头主叫号码）</li>
 *   <li>authType=0（不认证，IP 型网关）</li>
 *   <li>transportProtocol=1（UDP）</li>
 *   <li>status=0（启用，按 GatewayInfo 约定：0-启用，1-禁用）</li>
 * </ul>
 * <p>
 * 与默认实现的差异：
 * <ul>
 *   <li>默认实现 {@code DefaultGatewayProvider} 通过 JdbcTemplate 查询 H2 seed 数据；</li>
 *   <li>本实现直接硬编码返回，去除数据库依赖。</li>
 * </ul>
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomGatewayProvider implements GatewayProvider {

    /** 硬编码网关 ID（字符串形式，对应 GatewayInfo.id 约定） */
    private static final String GW_ID = "1";
    /** 硬编码网关地址 */
    private static final String GW_ADDRESS = "127.0.0.1";
    /** 硬编码网关端口 */
    private static final Integer GW_PORT = 5080;

    /**
     * 按网关 ID 查询（硬编码匹配）。
     *
     * @param gatewayId 网关 ID（字符串形式）
     * @return id="1" 时返回硬编码网关，否则 null
     */
    @Override
    public GatewayInfo getGatewayById(String gatewayId) {
        if (!GW_ID.equals(gatewayId)) {
            log.debug("[getGatewayById][未命中硬编码网关] gatewayId={}", gatewayId);
            return null;
        }
        return buildHardcodedGateway();
    }

    /**
     * 按地址和端口查询网关（用于入呼来源识别）。
     *
     * @param address 网关地址（IP或域名）
     * @param port    网关端口
     * @return address=127.0.0.1 且 port=5080 时返回硬编码网关，否则 null
     */
    @Override
    public GatewayInfo getGatewayByAddress(String address, Integer port) {
        if (!GW_ADDRESS.equals(address) || !GW_PORT.equals(port)) {
            log.debug("[getGatewayByAddress][未命中硬编码网关] address={}, port={}", address, port);
            return null;
        }
        return buildHardcodedGateway();
    }

    /**
     * 获取所有启用的网关列表。
     * <p>
     * 设计说明：返回不可变单元素列表（{@link Collections#singletonList}），
     * 包含硬编码的启用网关（status=0）。
     *
     * @return 包含单条硬编码网关的不可变列表
     */
    @Override
    public List<GatewayInfo> listEnabledGateways() {
        log.debug("[listEnabledGateways][返回硬编码网关列表] name=test-gw");
        return Collections.singletonList(buildHardcodedGateway());
    }

    /**
     * 构造硬编码网关信息对象。
     * <p>
     * 设计说明：每次调用构造新对象，避免外部修改污染硬编码数据。
     *
     * @return 硬编码网关信息
     */
    private GatewayInfo buildHardcodedGateway() {
        GatewayInfo gateway = new GatewayInfo();
        gateway.setId(GW_ID);
        gateway.setName("test-gw");
        gateway.setAddress(GW_ADDRESS);
        gateway.setPort(GW_PORT);
        gateway.setExternalLineNumber("10086");
        gateway.setFromDomain("sipproxy.example");
        gateway.setCallerIdInFrom(1);
        gateway.setAuthType(0);
        gateway.setTransportProtocol(1);
        gateway.setStatus(0);
        return gateway;
    }
}
