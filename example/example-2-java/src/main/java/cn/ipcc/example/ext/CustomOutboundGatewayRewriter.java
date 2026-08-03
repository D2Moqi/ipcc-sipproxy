package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.gateway.OutboundGatewayRewriter;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.PeerUnavailableException;
import javax.sip.SipFactory;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.message.Request;

/**
 * 出局信令改写扩展点自定义实现（替代 {@code DefaultOutboundGatewayRewriter}）。
 * <p>
 * 用途：自定义出局 INVITE 的头域改写逻辑，满足运营商中继网关对主叫号码、PAI 头域的要求。
 * 改写时机：sipproxy 在选定目标网关后、转发 INVITE 到第三方网关前调用。
 * <p>
 * 数据来源：通过方法入参 {@link GatewayInfo} 获取改写参数（externalLineNumber、fromDomain 等），
 * 网关信息由 {@link CustomGatewayProvider} 硬编码提供。
 * <p>
 * 与默认实现的差异：
 * <ul>
 *   <li>默认实现 {@code DefaultOutboundGatewayRewriter} 执行标准 6 步改写
 *       （From User/Domain、Contact、PAI、Authorization、Route、User-Agent）；</li>
 *   <li>本实现简化为标准 3 步改写：
 *     <ol>
 *       <li>From 头改写：user 改为 externalLineNumber（DID/线路号码），domain 改为 fromDomain</li>
 *       <li>P-Asserted-Identity 注入：使用 externalLineNumber 作为 DID，便于运营商透传</li>
 *       <li>Record-Route 移除：避免运营商网关误把 sipproxy 当作下一跳</li>
 *     </ol>
 *   </li>
 *   <li>不包含 Contact、Authorization、Route、User-Agent 改写。</li>
 * </ul>
 * <p>
 * 注意：本实现仅做头域改写，不修改 Request-URI（由 {@code SipMessageForwarder.modifyHeadersForForwarding} 统一处理）。
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomOutboundGatewayRewriter implements OutboundGatewayRewriter {

    private final AddressFactory addressFactory;
    private final HeaderFactory headerFactory;

    /**
     * 构造方法初始化 JAIN-SIP 工厂。
     * <p>
     * 设计说明：JAIN-SIP {@link SipFactory} 为单例，{@code createAddressFactory}/{@code createHeaderFactory}
     * 返回的工厂实例无状态可重复创建，不会与 {@code SipProxyService} 创建的工厂冲突。
     *
     * @throws PeerUnavailableException JAIN-SIP 实现类加载失败（构造为 Bean 时由 Spring 包装为 BeanCreationException）
     */
    public CustomOutboundGatewayRewriter() throws PeerUnavailableException {
        SipFactory sipFactory = SipFactory.getInstance();
        sipFactory.setPathName(SipProxyConstants.SIP_STACK_PATH);
        this.addressFactory = sipFactory.createAddressFactory();
        this.headerFactory = sipFactory.createHeaderFactory();
    }

    /**
     * 改写出局请求（标准 3 步改写）。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>From 头改写：user 改为 externalLineNumber，domain 改为 fromDomain（缺省回退到网关地址）</li>
     *   <li>P-Asserted-Identity 注入：使用 externalLineNumber 作为 DID</li>
     *   <li>移除 Record-Route 头域</li>
     * </ol>
     *
     * @param request     出局 SIP 请求（INVITE 为主）
     * @param gatewayInfo 目标网关信息（提供 externalLineNumber、fromDomain、address 等改写参数）
     */
    @Override
    public void rewrite(Request request, GatewayInfo gatewayInfo) {
        String gatewayIp = gatewayInfo.getAddress();
        String callId = SipAnalysisUtil.getCallId(request);
        log.info("[rewrite][开始出局信令改写] callId={}, gatewayId={}", callId, gatewayInfo.getId());

        // 解析 fromDomain：网关配置优先，缺省回退到网关 IP
        String fromDomain = gatewayInfo.getFromDomain();
        if (fromDomain == null || fromDomain.isEmpty()) {
            fromDomain = gatewayIp;
        }
        // From 头 user 改为 externalLineNumber（DID/线路号码）
        String fromNumber = gatewayInfo.getExternalLineNumber();

        // 第1步：改写 From 头（user=externalLineNumber, domain=fromDomain）
        if (fromNumber != null && !fromNumber.isEmpty()) {
            try {
                SipURI fromUri = addressFactory.createSipURI(fromNumber, fromDomain);
                Address fromAddress = addressFactory.createAddress(fromUri);
                FromHeader newFromHeader = headerFactory.createFromHeader(fromAddress, null);
                request.removeHeader(FromHeader.NAME);
                request.addHeader(newFromHeader);
                log.info("[rewrite][已改写From头] callId={}, fromNumber={}, fromDomain={}", callId, fromNumber, fromDomain);
            } catch (Exception e) {
                log.error("[rewrite][改写From头失败] callId={}", callId, e);
            }
        }

        // 第2步：注入 P-Asserted-Identity（使用 externalLineNumber 作为 DID，便于运营商透传）
        if (fromNumber != null && !fromNumber.isEmpty()) {
            try {
                SipURI paiUri = addressFactory.createSipURI(fromNumber, fromDomain);
                Address paiAddress = addressFactory.createAddress(paiUri);
                Header paiHeader = headerFactory.createHeader("P-Asserted-Identity",
                        "<" + paiAddress.toString() + ">");
                request.removeHeader("P-Asserted-Identity");
                request.addHeader(paiHeader);
                log.info("[rewrite][已注入P-Asserted-Identity] callId={}, pai={}", callId, paiHeader);
            } catch (Exception e) {
                log.error("[rewrite][注入PAI失败] callId={}", callId, e);
            }
        }

        // 第3步：移除 Record-Route 头域，避免运营商网关误把 sipproxy 当作下一跳
        request.removeHeader("Record-Route");
        log.info("[rewrite][已移除Record-Route头域] callId={}", callId);

        log.info("[rewrite][出局信令改写完成] callId={}, gatewayId={}", callId, gatewayInfo.getId());
    }
}
