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
import javax.sip.address.URI;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.RouteHeader;
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
 *   <li>Request-URI 改写：改为 sip:被叫号@网关address（不带端口，与第三方 FS 注册域对齐，问题16环路+问题19本地投递）</li>
 *   <li>Route 头注入：指向网关 address:port（含 transport），保障 Request-URI 去端口后的信令传输路由</li>
 *   <li>From 头改写：按 {@code callerIdInFrom} 决定主叫号码，按 {@code fromDomain} 或网关地址作为域名</li>
 *   <li>P-Asserted-Identity 注入：使用原始主叫号码，便于运营商透传真实主叫</li>
 *   <li>Record-Route 移除：避免运营商网关误把 sipproxy 当作下一跳</li>
 *   <li>X-IPCC-Outbound 标记注入：用于入站方向识别“出局报文被路由回 proxy”的环路（配合豁免分支拒绝）</li>
 * </ol>
 *
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

        // 问题16修复+问题19修复：将 Request-URI 改写为 sip:被叫号@网关address（不带端口）。
        // 带端口的根因：若 Request-URI host 是 proxy 隧道地址，第三方网关（B2BUA）会按 Request-URI
        // 把呼叫路由回 proxy 形成 INVITE 乒乓环路；但带网关端口（如 @62.234.191.165:9988）时，
        // 第三方 FS 本地投递按注册域匹配（注册域为无端口 address），带端口 URI 无法命中已注册被叫，
        // 反而新建 B2BUA 腿送回隧道被 482 环路防护拦截。故 Request-URI 去掉端口与注册域对齐；
        // 信令传输路由改由下方注入的 Route 头保障（无端口 URI 不能直接作为投递目标，
        // 否则 JAIN-SIP 按默认 5060 发送导致信令不可达）。
        try {
            URI requestUri = request.getRequestURI();
            if (requestUri instanceof SipURI requestSipUri) {
                String calledNumber = requestSipUri.getUser();
                SipURI newRequestUri = addressFactory.createSipURI(calledNumber, gatewayIp);
                // 注意：不调用 setPort，保证 Request-URI 与第三方 FS 注册域（无端口）一致，命中本地投递
                request.setRequestURI(newRequestUri);
                log.info("[rewrite][已改写Request-URI] callId={}, newRequestUri={}", callId, newRequestUri);
            }
        } catch (Exception e) {
            log.error("[rewrite][改写Request-URI失败] callId={}", callId, e);
        }

        // 问题19配套：注入 Route 头指向网关真实监听地址:端口（含 transport 参数）。
        // JAIN-SIP 发送时按顶层 Route 头解析下一跳（优先于 Request-URI），
        // 保证 Request-URI 去端口后信令仍投递到网关实际监听端口（如 9988）。
        try {
            int gatewayPort = gatewayInfo.getPort() != null ? gatewayInfo.getPort() : 5060;
            // transportProtocol：1=UDP（默认），2=TCP（与 cc_sipproxy_gateway 配置语义一致）
            String transport = Integer.valueOf(2).equals(gatewayInfo.getTransportProtocol())
                    ? SipProxyConstants.TRANSPORT_TCP : SipProxyConstants.TRANSPORT_UDP;
            SipURI routeUri = addressFactory.createSipURI(null, gatewayIp);
            routeUri.setPort(gatewayPort);
            routeUri.setTransportParam(transport);
            RouteHeader routeHeader = headerFactory.createRouteHeader(addressFactory.createAddress(routeUri));
            request.removeHeader(RouteHeader.NAME);
            request.addHeader(routeHeader);
            log.info("[rewrite][已注入Route头保障传输路由] callId={}, route={}", callId, routeHeader);
        } catch (Exception e) {
            log.error("[rewrite][注入Route头失败] callId={}", callId, e);
        }

        // 问题16环路防护标记：出局报文注入 X-IPCC-Outbound 头（保留 X-头透传语义，不删除已有 X-头）。
        // 第三方网关若将本出局报文（含透传的 X-头）路由回 proxy，入站方向 SipInviteRequestHandler
        // 检测到该标记即判定为环路，拒绝再次出局（返回 482 Loop Detected）。
        try {
            request.removeHeader(SipProxyConstants.HEADER_OUTBOUND_MARK);
            request.addHeader(headerFactory.createHeader(SipProxyConstants.HEADER_OUTBOUND_MARK, "true"));
        } catch (Exception e) {
            log.error("[rewrite][注入出局标记头失败] callId={}", callId, e);
        }

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
                // 问题30修复: 保留原始 From tag(dialog 标识关键部分)。
                // From tag 由发起方(CC FS)UAC 生成,第三方网关回 200 OK 时原样回显;
                // 若此处丢失,CC FS 按 RFC3261 §12.2(Call-ID+From tag+To tag)无法匹配
                // dialog,sofia 丢弃 200 OK → 无 ACK → Timer B 超时 408
                FromHeader originalFrom = (FromHeader) request.getHeader(FromHeader.NAME);
                String originalTag = originalFrom != null ? originalFrom.getTag() : null;
                SipURI fromUri = buildSipUri(fromNumber, fromDomain);
                Address fromAddress = addressFactory.createAddress(fromUri);
                FromHeader newFromHeader = headerFactory.createFromHeader(fromAddress, originalTag);
                request.removeHeader(FromHeader.NAME);
                request.addHeader(newFromHeader);
                log.info("[rewrite][已改写From头] callId={}, fromNumber={}, fromDomain={}, useCallerIdInFrom={}, 保留原始FromTag={}",
                        callId, fromNumber, fromDomain, useCallerIdInFrom, originalTag != null);
            } catch (Exception e) {
                log.error("[rewrite][改写From头失败] callId={}", callId, e);
            }
        }

        // 注入 P-Asserted-Identity（使用原始主叫号码，便于运营商透传真实主叫）
        if (originalCaller != null && !originalCaller.isEmpty()) {
            try {
                SipURI paiUri = buildSipUri(originalCaller, fromDomain);
                // 注意：必须使用 URI 的 toString（sip:user@host:port）再包一层尖括号；
                // 若使用 Address.toString()，JAIN-SIP 对无 displayName 的 name-addr 已输出 <sip:...> 形式，
                // 再包一层会产生 <<sip:...>> 导致 createHeader ParseException（回归修复）
                Header paiHeader = headerFactory.createHeader("P-Asserted-Identity",
                        "<" + paiUri.toString() + ">");
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

    /**
     * 构造 SIP URI（兼容 host 带端口场景）
     * <p>
     * 修复背景：fromDomain 缺省回退网关地址时可能为 "host:port" 形式（如 62.234.191.165:9988），
     * 直接传入 addressFactory.createSipURI(user, "host:port") 时 JAIN-SIP 将 host 整体
     * 按 GenericURI 解析抛 ParseException（Bad URI format），导致 PAI/From 改写失败。
     * 此处先拆分 host 与 port，再用 SipURI.setPort 显式设置，保证生成标准 sip:user@host:port 语法。
     *
     * @param user   URI user 部分（号码）
     * @param domain URI host 部分，允许携带 :port（兼容 IPv4/域名，IPv6 不在本网关场景内）
     * @return 构造完成的 SipURI
     * @throws Exception URI 构造异常
     */
    private SipURI buildSipUri(String user, String domain) throws Exception {
        String host = domain;
        int port = -1;
        int colonIdx = domain.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < domain.length() - 1) {
            try {
                port = Integer.parseInt(domain.substring(colonIdx + 1));
                host = domain.substring(0, colonIdx);
            } catch (NumberFormatException e) {
                // 冒号后非数字端口（异常配置），保持原 host 不拆分
                log.warn("[buildSipUri][domain端口解析失败,按原样使用] domain={}", domain);
            }
        }
        SipURI uri = addressFactory.createSipURI(user, host);
        if (port > 0) {
            uri.setPort(port);
        }
        return uri;
    }
}
