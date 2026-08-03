package cn.ipcc.sipproxy.defaults.gateway;

import cn.ipcc.sipproxy.api.gateway.OutboundGatewayRewriter;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import lombok.extern.slf4j.Slf4j;

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
 * 出局信令改写默认实现
 * <p>
 * 设计意图：执行标准出局 INVITE 头域改写（From 头、P-Asserted-Identity、Record-Route 清理），
 * 满足运营商中继网关对主叫号码、PAI 头域的要求。父程序若需自定义改写规则
 * （如注入 Authorization、修改 Contact、添加 Route 头等），实现 {@link OutboundGatewayRewriter}
 * 接口注册为 Bean 即可覆盖。
 * <p>
 * 改写步骤：
 * <ol>
 *   <li>From 头改写：按 {@code callerIdInFrom} 决定主叫号码，按 {@code fromDomain} 或网关地址作为域名</li>
 *   <li>P-Asserted-Identity 注入：使用原始主叫号码，便于运营商透传真实主叫</li>
 *   <li>Record-Route 移除：避免运营商网关误把 sipproxy 当作下一跳</li>
 * </ol>
 * <p>
 * 注意：本实现仅做头域改写，不修改 Request-URI（由 {@code SipMessageForwarder.modifyHeadersForForwarding} 统一处理）。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultOutboundGatewayRewriter implements OutboundGatewayRewriter {

    private final AddressFactory addressFactory;
    private final HeaderFactory headerFactory;

    /**
     * 构造方法初始化 JAIN-SIP 工厂
     * <p>
     * 设计说明：JAIN-SIP {@link SipFactory} 为单例，{@code createAddressFactory}/{@code createHeaderFactory}
     * 返回的工厂实例无状态可重复创建，不会与 {@code SipProxyService} 创建的工厂冲突。
     *
     * @throws PeerUnavailableException JAIN-SIP 实现类加载失败
     */
    public DefaultOutboundGatewayRewriter() throws PeerUnavailableException {
        SipFactory sipFactory = SipFactory.getInstance();
        sipFactory.setPathName(SipProxyConstants.SIP_STACK_PATH);
        this.addressFactory = sipFactory.createAddressFactory();
        this.headerFactory = sipFactory.createHeaderFactory();
    }

    /**
     * 改写出局请求
     * <p>
     * 处理流程：
     * <ol>
     *   <li>提取原始 From 头用户号码（用于 PAI 注入）</li>
     *   <li>解析 fromDomain：网关配置优先，缺省回退到网关地址</li>
     *   <li>按 {@code callerIdInFrom} 决定 From 头用户号码：
     *     <ul>
     *       <li>callerIdInFrom=0：使用原始主叫号码</li>
     *       <li>其他值：使用网关 externalLineNumber（DID/线路号码）</li>
     *     </ul>
     *   </li>
     *   <li>重写 From 头（user + domain）</li>
     *   <li>注入 P-Asserted-Identity（使用原始主叫号码，便于运营商透传）</li>
     *   <li>移除 Record-Route 头域</li>
     * </ol>
     *
     * @param request     出局 SIP 请求（INVITE 为主）
     * @param gatewayInfo 目标网关信息（提供 fromDomain、externalLineNumber、callerIdInFrom、address 等改写参数）
     */
    @Override
    public void rewrite(Request request, GatewayInfo gatewayInfo) {
        String gatewayIp = gatewayInfo.getAddress();
        String callId = SipAnalysisUtil.getCallId(request);
        log.info("[rewrite][开始出局信令改写] callId={}, gatewayId={}", callId, gatewayInfo.getId());

        // 提取原始主叫号码（用于 PAI 注入）
        String originalCaller = SipAnalysisUtil.extractFromUser(request);

        // 解析 fromDomain：网关配置优先，缺省回退到网关 IP
        String fromDomain = gatewayInfo.getFromDomain();
        if (fromDomain == null || fromDomain.isEmpty()) {
            fromDomain = gatewayIp;
        }

        // 按 callerIdInFrom 决定 From 头用户号码
        // 0=使用原始主叫号码；其他值=使用 externalLineNumber（DID/线路号码）
        boolean useCallerIdInFrom = gatewayInfo.getCallerIdInFrom() != null && gatewayInfo.getCallerIdInFrom() == 0;
        String fromNumber = useCallerIdInFrom ? originalCaller : gatewayInfo.getExternalLineNumber();

        // 改写 From 头
        if (fromNumber != null && !fromNumber.isEmpty()) {
            try {
                SipURI fromUri = addressFactory.createSipURI(fromNumber, fromDomain);
                Address fromAddress = addressFactory.createAddress(fromUri);
                FromHeader newFromHeader = headerFactory.createFromHeader(fromAddress, null);
                request.removeHeader(FromHeader.NAME);
                request.addHeader(newFromHeader);
                log.info("[rewrite][已改写From头] callId={}, fromNumber={}, fromDomain={}, useCallerIdInFrom={}",
                        callId, fromNumber, fromDomain, useCallerIdInFrom);
            } catch (Exception e) {
                log.error("[rewrite][改写From头失败] callId={}", callId, e);
            }
        }

        // 注入 P-Asserted-Identity（使用原始主叫号码，便于运营商透传真实主叫）
        if (originalCaller != null && !originalCaller.isEmpty()) {
            try {
                SipURI paiUri = addressFactory.createSipURI(originalCaller, fromDomain);
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

        // 移除 Record-Route 头域，避免运营商网关误把 sipproxy 当作下一跳
        request.removeHeader("Record-Route");
        log.info("[rewrite][已移除Record-Route头域] callId={}", callId);

        log.info("[rewrite][出局信令改写完成] callId={}, gatewayId={}", callId, gatewayInfo.getId());
    }
}
