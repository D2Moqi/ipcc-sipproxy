package cn.ipcc.sipproxy.core.handler.request.sip;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.autoconfigure.SipProxyProperties;
import cn.ipcc.sipproxy.core.annotation.SipMethod;
import cn.ipcc.sipproxy.core.register.GatewayRegistry;
import cn.ipcc.sipproxy.core.utils.SipAnalysisUtil;
import cn.ipcc.sipproxy.support.RedisConstants;
import cn.ipcc.sipproxy.support.SipProxyConstants;
import cn.ipcc.sipproxy.support.model.GatewayInfo;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sip.SipProvider;
import javax.sip.address.SipURI;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

import java.util.concurrent.TimeUnit;

/**
 * SIP 通道 REGISTER 请求处理器（4G 网关注册）
 * <p>
 * 设计意图：处理经 UDP/TCP 直连 sipproxy 的第三方网关 REGISTER（网关→sipproxy 注册方向），
 * 完成 Digest 认证并缓存注册绑定（GatewayRegistry），使注册型网关的呼入来源可识别、
 * 呼出目标可解析（注册 Contact > 静态配置）。坐席 WebSocket 注册仍走 WsRegisterRequestHandler，
 * 与本处理器互不影响。
 * <p>
 * 处理流程：
 * <ol>
 *   <li>无 Authorization 头 → 401 挑战（realm 按 From user 反查网关回退链解析，保证与认证同源）</li>
 *   <li>携带 Authorization → 按 username 反查网关（getGatewayByUsername）并校验认证型/启用状态</li>
 *   <li>Digest 校验（MD5，HA1=MD5(username:realm:password)）→ 200 OK + GatewayRegistry.bind</li>
 *   <li>Expires=0 → 注销绑定；校验失败/账号未匹配 → 403</li>
 * </ol>
 * <p>
 * 响应发送说明：handler 无 RequestEvent 上下文，响应经注入的 SipProvider 获取/创建服务端事务发送
 * （参照 {@code SipProxyService.cacheInboundChannelForInvite} 路径③的事务获取模式）。
 *
 * @author ipcc
 */
@Slf4j
@Component
@SipMethod(SipAnalysisUtil.REGISTER)
public class SipRegisterRequestHandler extends AbstractSipRequestHandler {

    /** 注册有效期缺省值（秒）：网关未携带 Expires 头时使用 */
    private static final long DEFAULT_EXPIRES_SECONDS = 3600L;
    /** 注册有效期上限缺省值（秒）：网关表 registerMaxExpires 未配置时使用 */
    private static final long DEFAULT_MAX_EXPIRES_SECONDS = 7200L;
    /** 缺省端口：网关 Contact 未携带端口时按 5060 处理 */
    private static final int DEFAULT_CONTACT_PORT = 5060;
    /** Digest nonce 有效期（秒）：401 下发的 nonce 仅在此窗口内有效，防捕获报文无限重放 */
    private static final long NONCE_TTL_SECONDS = 300L;

    @Resource
    private GatewayProvider gatewayProvider;

    @Resource
    private GatewayRegistry gatewayRegistry;

    @Resource
    private SipProxyProperties properties;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 默认请求处理器（未命中注册账号的 REGISTER 回退以保持既有透传行为） */
    @Resource
    private SipDefaultRequestHandler sipDefaultRequestHandler;

    /** SIP 发送 Provider（SipProxyService.initializeHandlers 注入，用于创建服务端事务回送响应） */
    @Setter
    private SipProvider sipProvider;

    /**
     * 处理第三方网关 REGISTER
     * <p>
     * 仅处理 THIRD_PARTY 来源（来源识别已对 REGISTER 按注册账号特判，见
     * DefaultMessageSourceIdentifier 第 0.5 层）；FS 来源的 REGISTER 回退默认处理器
     * 保持既有透传行为。未配置注册账号的存量网关 REGISTER（源 IP 命中静态网关
     * 列表被识别为 THIRD_PARTY）同样回退默认处理器，避免新处理器阻断存量注册链路。
     *
     * @param request SIP REGISTER 请求
     * @param callId  Call-ID
     * @param source  消息来源（应为 THIRD_PARTY）
     */
    @Override
    public void handle(Request request, String callId, String source) throws Exception {
        if (!SipProxyConstants.THIRD_PARTY.equals(source)) {
            log.info("[handle][非第三方来源的REGISTER,回退默认处理器] source={}, callId={}", source, callId);
            // 存量行为：REGISTER 此前无专用处理器，统一走默认处理器透传；
            // 坐席 WebSocket 注册走 WsRegisterRequestHandler 通道，不经过此处
            sipDefaultRequestHandler.handle(request, callId, source);
            return;
        }
        String fromUser = SipAnalysisUtil.getFromUser(request);
        AuthorizationHeader auth = SipAnalysisUtil.getAuthorization(request);
        if (auth == null) {
            // ① 无凭证 → 反查网关：命中注册账号 → 401 挑战，否则回退默认处理器
            //    realm 按 From user(注册账号)反查网关解析，保证 401 下发的 realm 与后续
            //    认证计算使用的 realm 同源（Digest 客户端以 401 返回的 realm 计算 response，
            //    两处不一致会认证失败）
            GatewayInfo probe = gatewayProvider.getGatewayByUsername(fromUser);
            if (probe == null) {
                log.info("[handle][REGISTER账号未配置,回退默认处理器] fromUser={}, callId={}", fromUser, callId);
                sipDefaultRequestHandler.handle(request, callId, source);
                return;
            }
            send401(request, probe);
            return;
        }
        // ② 按注册账号反查网关并校验认证型/启用状态
        GatewayInfo gateway = gatewayProvider.getGatewayByUsername(auth.getUsername());
        if (gateway == null) {
            log.info("[handle][REGISTER账号未配置,回退默认处理器] username={}, callId={}", auth.getUsername(), callId);
            // 存量静态网关（未配置注册账号）的 REGISTER 保持旧透传行为，不误拦
            sipDefaultRequestHandler.handle(request, callId, source);
            return;
        }
        if (!Integer.valueOf(1).equals(gateway.getAuthType())) {
            log.warn("[handle][注册账号未启用网关或非认证型] username={}, gatewayExists={}",
                    auth.getUsername(), gateway != null);
            sendResponse(request, Response.FORBIDDEN);
            return;
        }
        // ③ Digest 校验（realm 与 401 下发同源；nonce 必须为本服务下发且未过期）
        if (!verifyDigest(gateway, auth, request)) {
            log.error("[handle][网关注册Digest校验失败] username={}, gatewayId={}", auth.getUsername(), gateway.getId());
            sendResponse(request, Response.FORBIDDEN);
            return;
        }
        // ④ 解析有效期：Expires=0 注销；否则受 registerMaxExpires 上限约束后绑定
        long expires = extractExpires(request, gateway);
        if (expires <= 0) {
            gatewayRegistry.unbind(Long.valueOf(gateway.getId()));
            sendResponse(request, Response.OK);
            log.info("[handle][网关注册注销成功] username={}, gatewayId={}", auth.getUsername(), gateway.getId());
            return;
        }
        String[] contact = extractContact(request);
        String sourceIp = SipAnalysisUtil.getSourceIpFromMessage(request);
        gatewayRegistry.bind(gateway, contact[0],
                contact[1] != null ? Integer.valueOf(contact[1]) : null,
                contact[2], expires, sourceIp);
        sendResponse(request, Response.OK);
        log.info("[handle][网关注册成功] username={}, gatewayId={}, expires={}",
                auth.getUsername(), gateway.getId(), expires);
    }

    /**
     * 标准 Digest 校验（与 DefaultSipAuthenticationProvider 同算法）
     * <p>
     * HA1=MD5(username:realm:password)、HA2=MD5(method:uri)、response=MD5(HA1:nonce:HA2)。
     * 兼容客户端回显不同 realm 的固定配置（此时按客户端 realm 计算，保证设备侧可配）。
     * <p>
     * nonce 校验：必须为本服务 401 下发且未过期（Redis 票据存储，TTL={@link #NONCE_TTL_SECONDS}），
     * 校验通过后删除票据（一次性使用），防止网络上捕获的 (nonce, response) 报文无限重放
     * 改写注册绑定（呼入/呼出被劫持）。
     *
     * @param gateway 网关信息（提供 password/registerRealm/fromDomain）
     * @param auth    Authorization 头
     * @param request REGISTER 请求
     * @return 校验通过返回 true
     */
    private boolean verifyDigest(GatewayInfo gateway, AuthorizationHeader auth, Request request) {
        String realm = resolveRealm(gateway);
        if (auth.getRealm() != null && !realm.equals(auth.getRealm())) {
            // 客户端回显 realm 不一致时按客户端值校验（兼容设备侧固定 realm 配置）
            log.debug("[verifyDigest][按客户端realm校验] serverRealm={}, clientRealm={}", realm, auth.getRealm());
            realm = auth.getRealm();
        }
        // nonce 票据校验：未下发/已过期/已使用的一律拒绝（防重放）
        Boolean nonceValid = null;
        try {
            nonceValid = stringRedisTemplate.hasKey(RedisConstants.SIP_NONCE_PREFIX + auth.getNonce());
        } catch (Exception e) {
            log.warn("[verifyDigest][nonce校验异常,按不通过处理] username={}, msg={}", auth.getUsername(), e.getMessage());
        }
        if (!Boolean.TRUE.equals(nonceValid)) {
            log.warn("[verifyDigest][nonce无效或已过期,拒绝REGISTER] username={}", auth.getUsername());
            return false;
        }
        String ha1 = DigestUtil.md5Hex(auth.getUsername() + ":" + realm + ":" + gateway.getPassword());
        String ha2 = DigestUtil.md5Hex(request.getMethod() + ":" + auth.getURI());
        String expected = DigestUtil.md5Hex(ha1 + ":" + auth.getNonce() + ":" + ha2);
        boolean verified = expected.equals(auth.getResponse());
        if (verified) {
            // 一次性 nonce：校验通过立即删除票据，同报文无法二次通过
            try {
                stringRedisTemplate.delete(RedisConstants.SIP_NONCE_PREFIX + auth.getNonce());
            } catch (Exception e) {
                log.warn("[verifyDigest][nonce票据删除失败,TTL兜底过期] username={}, msg={}", auth.getUsername(), e.getMessage());
            }
        }
        return verified;
    }

    /**
     * realm 回退链：register_realm(网关表) > from_domain(网关表) > sip.public-ip(部署级兜底)
     *
     * @param gateway 网关信息（可能为 null）
     * @return Digest realm 值
     */
    private String resolveRealm(GatewayInfo gateway) {
        if (gateway != null && StrUtil.isNotBlank(gateway.getRegisterRealm())) {
            return gateway.getRegisterRealm();
        }
        if (gateway != null && StrUtil.isNotBlank(gateway.getFromDomain())) {
            return gateway.getFromDomain();
        }
        String publicIp = properties.getSip().getPublicIp();
        return StrUtil.isNotBlank(publicIp) ? publicIp : "sipproxy";
    }

    /**
     * 提取注册有效期（秒）
     * <p>
     * 处理逻辑：优先 Expires 头，缺省 3600；取 min(请求值, registerMaxExpires 上限)，
     * 上限缺省 7200，防止网关请求超长有效期。
     *
     * @param request REGISTER 请求
     * @param gateway 网关信息（提供 registerMaxExpires）
     * @return 注册有效期（秒），Expires=0 时返回 0 表示注销
     */
    private long extractExpires(Request request, GatewayInfo gateway) {
        long expires = DEFAULT_EXPIRES_SECONDS;
        ExpiresHeader expiresHeader = (ExpiresHeader) request.getHeader(ExpiresHeader.NAME);
        if (expiresHeader != null && expiresHeader.getExpires() >= 0) {
            expires = expiresHeader.getExpires();
        }
        if (expires <= 0) {
            return 0;
        }
        long max = gateway.getRegisterMaxExpires() != null
                ? gateway.getRegisterMaxExpires() : DEFAULT_MAX_EXPIRES_SECONDS;
        return Math.min(expires, max);
    }

    /**
     * 提取首个 Contact 的 host/port/transport
     * <p>
     * 处理逻辑：JAIN-SIP ContactHeader 取值，host 取 URI host，port 缺省 null（调用方按 5060），
     * transport 取 URI transport 参数（缺省 null，调用方按 udp）。
     *
     * @param request REGISTER 请求
     * @return [host, port, transport]，任一缺失为 null；无 Contact 返回 [null,null,null]
     */
    private String[] extractContact(Request request) {
        ContactHeader contactHeader = (ContactHeader) request.getHeader(ContactHeader.NAME);
        if (contactHeader == null || contactHeader.getAddress() == null
                || !(contactHeader.getAddress().getURI() instanceof SipURI sipUri)) {
            return new String[]{null, null, null};
        }
        String port = sipUri.getPort() > 0 ? String.valueOf(sipUri.getPort()) : null;
        return new String[]{sipUri.getHost(), port, sipUri.getTransportParam()};
    }

    /**
     * 发送 401 挑战（realm 按网关回退链解析；gateway 为 null 时用部署级兜底）
     * <p>
     * nonce 下发前写入 Redis 票据（TTL={@link #NONCE_TTL_SECONDS}），校验阶段按存储核对，
     * 保证 Digest 挑战同源且防重放。
     *
     * @param request REGISTER 请求
     * @param gateway 按 From user 反查的网关（可能为 null）
     */
    private void send401(Request request, GatewayInfo gateway) throws Exception {
        Response response = SipAnalysisUtil.buildResponse(request, Response.UNAUTHORIZED);
        String realm = gateway != null ? resolveRealm(gateway) : properties.getSip().getPublicIp();
        String nonce = IdUtil.fastSimpleUUID().replace("-", "");
        // nonce 票据落 Redis：校验阶段须能查到该 nonce，防捕获报文重放
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.SIP_NONCE_PREFIX + nonce, realm, NONCE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 票据写入失败时仍下发挑战（客户端将因 nonce 校验不过被拒，可被 401 重试覆盖）
            log.error("[send401][nonce票据写入Redis失败,后续校验将拒绝] msg={}", e.getMessage());
        }
        response.addHeader(headerFactory.createHeader("WWW-Authenticate",
                String.format("Digest realm=\"%s\", nonce=\"%s\"", realm, nonce)));
        sendViaServerTransaction(request, response);
    }

    /**
     * 发送 200/403 响应（经服务端事务）
     *
     * @param request    REGISTER 请求
     * @param statusCode 响应状态码
     */
    private void sendResponse(Request request, int statusCode) throws Exception {
        Response response = SipAnalysisUtil.buildResponse(request, statusCode);
        sendViaServerTransaction(request, response);
    }

    /**
     * 经服务端事务发送响应
     * <p>
     * 处理流程：优先按消息从栈内查找已存在事务；不存在则 getNewServerTransaction 创建；
     * 创建冲突（TransactionAlreadyExistsException）时回退栈内查找，保证幂等。
     * 发送失败仅记录日志（UDP 重传由 JAIN-SIP 栈按事务规则处理）。
     */
    private void sendViaServerTransaction(Request request, Response response) {
        try {
            javax.sip.ServerTransaction transaction = null;
            if (sipStack instanceof gov.nist.javax.sip.stack.SIPTransactionStack txStack) {
                transaction = (javax.sip.ServerTransaction) txStack.findTransaction(
                        (gov.nist.javax.sip.message.SIPMessage) request, true);
            }
            if (transaction == null && sipProvider != null) {
                transaction = sipProvider.getNewServerTransaction(request);
            }
            if (transaction == null) {
                log.error("[sendViaServerTransaction][无法获取服务端事务] method=REGISTER, statusCode={}", response.getStatusCode());
                return;
            }
            transaction.sendResponse(response);
            log.debug("[sendViaServerTransaction][响应已发送] method=REGISTER, statusCode={}", response.getStatusCode());
        } catch (Exception e) {
            log.error("[sendViaServerTransaction][响应发送失败] statusCode={}, msg={}",
                    response.getStatusCode(), e.getMessage(), e);
        }
    }
}