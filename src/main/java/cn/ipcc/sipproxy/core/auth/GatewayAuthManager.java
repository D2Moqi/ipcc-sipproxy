package cn.ipcc.sipproxy.core.auth;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.core.node.SipNodeManager;
import cn.ipcc.sipproxy.core.session.SessionInfo;
import cn.ipcc.sipproxy.core.session.SipSessionManager;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sip.SipProvider;
import javax.sip.address.AddressFactory;
import javax.sip.header.CSeqHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ProxyAuthenticateHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.Iterator;

/**
 * 网关认证管理器
 * <p>
 * 设计意图：统一管理SIP网关的Digest鉴权逻辑，从SipMessageForwarder中剥离407处理职责，
 * 支持RFC 2617标准Digest鉴权和qop=auth增强模式，修复原实现中预注入无效Authorization头、
 * 不支持stale重挑战、无qop支持等问题。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>出局INVITE预注入鉴权头策略管理（方案B：不预注入，裸INVITE等407）</li>
 *   <li>407 Proxy Authentication Required响应处理：解析挑战、计算Digest、重发INVITE</li>
 *   <li>循环防护：authChallengeCount计数，stale=true允许2次重试，stale=false仅1次</li>
 *   <li>Digest计算：支持无qop模式（兼容旧网关）和qop=auth模式（RFC 2617推荐）</li>
 * </ul>
 * <p>
 * 循环防护策略：
 * <pre>
 * authChallengeCount == 0：首次407，正常重试，计数+1
 * authChallengeCount == 1 且 stale == true：nonce过期重挑战，允许二次重试，计数+1
 * 其他情况（stale=false凭证错误或已达2次上限）：放弃重试，转发407给上层
 * </pre>
 *
 * @author ipcc
 */
@Slf4j
@Component
public class GatewayAuthManager {

    /**
     * 最大鉴权挑战次数（首次407 + stale重挑战共2次）
     */
    private static final int MAX_AUTH_CHALLENGE_COUNT = 2;

    @Resource
    private GatewayProvider gatewayProvider;

    @Resource
    private SipSessionManager sessionManager;

    @Resource
    private SipNodeManager nodeManager;

    private HeaderFactory headerFactory;
    private AddressFactory addressFactory;
    private SipProvider sipProvider;
    private SipProvider sipProviderTcp;

    /**
     * 初始化JAIN SIP工厂依赖（由SipProxyService启动时注入）
     */
    public void init(HeaderFactory headerFactory, AddressFactory addressFactory,
                     SipProvider sipProvider, SipProvider sipProviderTcp) {
        this.headerFactory = headerFactory;
        this.addressFactory = addressFactory;
        this.sipProvider = sipProvider;
        this.sipProviderTcp = sipProviderTcp;
    }

    /**
     * 出局INVITE预注入鉴权头
     * <p>
     * 方案选择：方案B（推荐）—— 不预注入任何鉴权头，直接发送裸INVITE等待407挑战。
     * 设计原因：SIP Digest鉴权的nonce必须由网关在407响应时下发，客户端无法预先知道正确nonce，
     * 预注入的Authorization头（response=""）必然触发407，省不了往返还增加网关日志噪音。
     *
     * @param request   INVITE请求
     * @param gatewayId 网关ID
     * @return true=已注入头，false=未注入头
     */
    public boolean preInjectAuthHeader(Request request, String gatewayId) {
        log.debug("[preInjectAuthHeader][方案B：不预注入鉴权头，裸INVITE发送等407] gatewayId={}", gatewayId);
        return false;
    }

    /**
     * 处理407 Proxy Authentication Required响应
     * <p>
     * 处理流程：
     * <ol>
     *   <li>循环防护：根据authChallengeCount和stale参数判断是否允许重试</li>
     *   <li>参数提取：从Proxy-Authenticate头解析realm、nonce、qop、algorithm、stale</li>
     *   <li>网关校验：查询GatewayInfo，校验authType=1且username/password非空</li>
     *   <li>请求重建：从SessionInfo.originalInviteText文本缓存重建原始INVITE对象</li>
     *   <li>Digest计算：支持无qop模式和qop=auth模式，包含cnonce、nc等参数</li>
     *   <li>头域注入：构造Proxy-Authorization头注入到重建的INVITE</li>
     *   <li>事务更新：CSeq+1、新Via branch，更新sessionInfo并缓存</li>
     *   <li>重发请求：按传输协议选择SipProvider发送</li>
     * </ol>
     *
     * @param response    407响应
     * @param sessionInfo 会话信息
     * @return true=重发成功（拦截407），false=无法重试（转发407）
     */
    public boolean handle407Challenge(Response response, SessionInfo sessionInfo) {
        String callId = sessionInfo.getCallId();
        try {
            int challengeCount = sessionInfo.getAuthChallengeCount();

            ProxyAuthenticateHeader proxyAuthHeader =
                    (ProxyAuthenticateHeader) response.getHeader(ProxyAuthenticateHeader.NAME);
            if (proxyAuthHeader == null) {
                log.error("[handle407Challenge][407响应无Proxy-Authenticate头] callId={}", callId);
                return false;
            }

            String realm = proxyAuthHeader.getRealm();
            String nonce = proxyAuthHeader.getNonce();
            String algorithm = proxyAuthHeader.getAlgorithm();
            if (algorithm == null || algorithm.isEmpty()) {
                algorithm = "MD5";
            }

            String qop = parseQop(proxyAuthHeader);
            boolean stale = parseStale(proxyAuthHeader);

            log.info("[handle407Challenge][收到407挑战] callId={}, challengeCount={}, realm={}, nonce={}, qop={}, stale={}",
                    callId, challengeCount, realm, nonce, qop, stale);

            if (!canRetry(challengeCount, stale, nonce, sessionInfo.getLast407Nonce(), callId)) {
                return false;
            }

            String gatewayId = sessionInfo.getGatewayId();
            if (gatewayId == null || gatewayId.isEmpty()) {
                log.error("[handle407Challenge][gatewayId为空，非出局呼叫] callId={}", callId);
                return false;
            }

            GatewayInfo gateway = gatewayProvider.getGatewayById(gatewayId);
            if (gateway == null) {
                log.error("[handle407Challenge][网关不存在或已禁用] gatewayId={}", gatewayId);
                return false;
            }
            if (gateway.getAuthType() == null || gateway.getAuthType() != 1) {
                log.warn("[handle407Challenge][网关未配置认证类型] gatewayId={}, authType={}", gatewayId, gateway.getAuthType());
                return false;
            }
            String username = gateway.getUsername();
            String password = gateway.getPassword();
            if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
                log.error("[handle407Challenge][网关未配置用户名或密码] gatewayId={}", gatewayId);
                return false;
            }

            String inviteText = sessionInfo.getOriginalInviteText();
            if (inviteText == null || inviteText.isEmpty()) {
                log.error("[handle407Challenge][原始INVITE文本缓存为空] callId={}", callId);
                return false;
            }
            Request inviteRequest;
            try {
                inviteRequest = SipAnalysisUtil.parseSipMessageRequest(inviteText);
            } catch (ParseException e) {
                log.error("[handle407Challenge][解析原始INVITE文本失败] callId={}", callId, e);
                return false;
            }

            String uriString = inviteRequest.getRequestURI().toString();
            String method = inviteRequest.getMethod();

            String digestResponse;
            String nc = String.format("%08x", challengeCount + 1);
            String cnonce = null;

            if ("auth".equalsIgnoreCase(qop)) {
                cnonce = IdUtil.fastSimpleUUID().substring(0, 16);
                digestResponse = calculateDigestQop(username, realm, password, method, uriString, nonce, nc, cnonce);
            } else {
                digestResponse = calculateDigest(username, realm, password, method, uriString, nonce);
            }

            StringBuilder authValue = new StringBuilder();
            authValue.append("Digest username=\"").append(username).append("\"")
                    .append(", realm=\"").append(realm).append("\"")
                    .append(", nonce=\"").append(nonce).append("\"")
                    .append(", uri=\"").append(uriString).append("\"")
                    .append(", response=\"").append(digestResponse).append("\"");
            if (algorithm != null && !algorithm.isEmpty() && !"MD5".equalsIgnoreCase(algorithm)) {
                authValue.append(", algorithm=").append(algorithm);
            }
            if ("auth".equalsIgnoreCase(qop)) {
                authValue.append(", qop=auth");
                authValue.append(", nc=").append(nc);
                authValue.append(", cnonce=\"").append(cnonce).append("\"");
            }

            javax.sip.header.Header proxyAuthorizationHeader = headerFactory.createHeader("Proxy-Authorization", authValue.toString());
            inviteRequest.removeHeader("Proxy-Authorization");
            inviteRequest.addHeader(proxyAuthorizationHeader);

            CSeqHeader cseqHeader = (CSeqHeader) inviteRequest.getHeader(CSeqHeader.NAME);
            if (cseqHeader != null) {
                cseqHeader.setSeqNumber(cseqHeader.getSeqNumber() + 1);
            }
            ViaHeader viaHeader = (ViaHeader) inviteRequest.getHeader(ViaHeader.NAME);
            if (viaHeader != null) {
                viaHeader.setBranch("z9hG4bK" + IdUtil.fastSimpleUUID());
            }

            sessionInfo.setAuthChallengeCount(challengeCount + 1);
            sessionInfo.setLast407Nonce(nonce);
            sessionManager.updateSessionInfo(sessionInfo);

            String transport = sessionInfo.getToSipTransport() != null ? sessionInfo.getToSipTransport() : "udp";
            SipProvider targetProvider = "tcp".equalsIgnoreCase(transport) ? sipProviderTcp : sipProvider;
            targetProvider.sendRequest(inviteRequest);

            log.info("[handle407Challenge][Digest鉴权重发INVITE成功] callId={}, gatewayId={}, username={}, qop={}",
                    callId, gatewayId, username, qop);
            return true;
        } catch (Exception e) {
            log.error("[handle407Challenge][处理407鉴权失败] callId={}", callId, e);
            return false;
        }
    }

    /**
     * 计算RFC 2617标准Digest响应值（无qop模式，兼容旧网关）
     */
    public String calculateDigest(String username, String realm, String password,
                                  String method, String uri, String nonce) {
        String ha1 = DigestUtil.md5Hex(username + ":" + realm + ":" + password);
        String ha2 = DigestUtil.md5Hex(method + ":" + uri);
        return DigestUtil.md5Hex(ha1 + ":" + nonce + ":" + ha2);
    }

    /**
     * 计算RFC 2617 qop=auth模式Digest响应值
     */
    public String calculateDigestQop(String username, String realm, String password,
                                     String method, String uri, String nonce,
                                     String nc, String cnonce) {
        String ha1 = DigestUtil.md5Hex(username + ":" + realm + ":" + password);
        String ha2 = DigestUtil.md5Hex(method + ":" + uri);
        return DigestUtil.md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
    }

    /**
     * 循环防护判断：是否允许重试
     * <p>
     * 策略：
     * - challengeCount == 0：首次407，允许重试
     * - challengeCount == 1 且 stale == true：nonce过期重挑战，允许二次重试
     * - 其他情况：不允许重试（凭证错误或已达上限）
     */
    private boolean canRetry(int challengeCount, boolean stale, String currentNonce, String lastNonce, String callId) {
        if (challengeCount >= MAX_AUTH_CHALLENGE_COUNT) {
            log.warn("[canRetry][超过最大重试次数{}，不再重试] callId={}", MAX_AUTH_CHALLENGE_COUNT, callId);
            return false;
        }
        if (challengeCount == 0) {
            return true;
        }
        if (challengeCount == 1 && stale) {
            if (currentNonce != null && !currentNonce.equals(lastNonce)) {
                log.info("[canRetry][stale=true且nonce更新，允许二次重试] callId={}, lastNonce={}, newNonce={}",
                        callId, lastNonce, currentNonce);
                return true;
            }
        }
        log.warn("[canRetry][凭证错误或nonce未更新，不再重试] callId={}, challengeCount={}, stale={}",
                callId, challengeCount, stale);
        return false;
    }

    /**
     * 从Proxy-Authenticate头解析qop参数
     */
    private String parseQop(ProxyAuthenticateHeader header) {
        try {
            Iterator<String> paramNames = header.getParameterNames();
            while (paramNames.hasNext()) {
                String paramName = paramNames.next();
                if ("qop".equalsIgnoreCase(paramName)) {
                    String qopValue = header.getParameter(paramName);
                    if (qopValue != null) {
                        qopValue = qopValue.replace("\"", "").trim();
                        if (qopValue.contains("auth")) {
                            return "auth";
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[parseQop][解析qop参数失败，降级为无qop模式]", e);
        }
        return null;
    }

    /**
     * 从Proxy-Authenticate头解析stale参数
     */
    private boolean parseStale(ProxyAuthenticateHeader header) {
        try {
            Iterator<String> paramNames = header.getParameterNames();
            while (paramNames.hasNext()) {
                String paramName = paramNames.next();
                if ("stale".equalsIgnoreCase(paramName)) {
                    String staleValue = header.getParameter(paramName);
                    return "true".equalsIgnoreCase(staleValue);
                }
            }
        } catch (Exception e) {
            log.debug("[parseStale][解析stale参数失败，默认false]", e);
        }
        return false;
    }
}
