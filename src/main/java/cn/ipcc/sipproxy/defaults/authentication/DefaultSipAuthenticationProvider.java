package cn.ipcc.sipproxy.defaults.authentication;

import cn.hutool.core.text.StrPool;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.ipcc.sipproxy.api.agent.AgentInfoProvider;
import cn.ipcc.sipproxy.api.authentication.SipAuthenticationProvider;
import cn.ipcc.sipproxy.support.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * SIP Digest 认证默认实现（本地 Digest 校验）
 * <p>
 * 设计意图：父程序未实现 {@link SipAuthenticationProvider} 时的兜底实现，
 * 通过 {@link AgentInfoProvider} 获取坐席密码后本地计算 Digest HA1/HA2/response 比对。
 * <p>
 * 此实现将原 {@code WsRegisterRequestHandler.validateDigestLocally} 的逻辑下沉到扩展点，
 * 使 {@code WsRegisterRequestHandler.validateCredentials} 统一委托给 {@link SipAuthenticationProvider}
 * 扩展点，不再需要 null 判断与分支降级。
 * <p>
 * 父程序实现 {@link SipAuthenticationProvider} 接口并注册为 Bean 即可覆盖此默认实现，
 * 完全接管 Digest 认证（适用于已有完整认证体系的场景，如对接外部鉴权系统、RADIUS 服务器等）。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultSipAuthenticationProvider implements SipAuthenticationProvider {

    private final AgentInfoProvider agentInfoProvider;

    /**
     * 构造方法注入坐席信息查询扩展点
     *
     * @param agentInfoProvider 坐席信息查询扩展点（用于获取坐席密码进行 Digest 计算）
     */
    public DefaultSipAuthenticationProvider(AgentInfoProvider agentInfoProvider) {
        this.agentInfoProvider = agentInfoProvider;
    }

    /**
     * 本地 Digest 校验
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>通过 AgentInfoProvider 查询坐席信息（含密码）</li>
     *   <li>计算 HA1 = MD5(username:realm:password)</li>
     *   <li>计算 HA2 = MD5(method:uri)</li>
     *   <li>计算 expectedResponse = MD5(HA1:nonce:HA2)</li>
     *   <li>比对 expectedResponse 与客户端提交的 response</li>
     * </ol>
     *
     * @param extension 分机号（Authorization 头的 username）
     * @param domain    认证域（Authorization 头的 realm）
     * @param nonce     401 响应下发的 nonce
     * @param uri       Authorization 头的 URI（Digest HA2 计算入参）
     * @param response  客户端计算的 Digest response 值
     * @param method    SIP 方法（REGISTER / INVITE 等，Digest HA2 计算入参）
     * @return 校验通过返回 true，失败返回 false
     */
    @Override
    public boolean authenticate(String extension, String domain, String nonce,
                                String uri, String response, String method) {
        AgentInfo agent = agentInfoProvider.getAgent(extension, domain);
        if (agent == null) {
            log.warn("[authenticate][未查询到坐席信息] extension={}, domain={}", extension, domain);
            return false;
        }
        if (StrUtil.isBlank(agent.getPassword())) {
            log.warn("[authenticate][坐席密码为空] extension={}, domain={}", extension, domain);
            return false;
        }
        try {
            String ha1 = DigestUtil.md5Hex(extension + StrPool.COLON + domain + StrPool.COLON + agent.getPassword());
            String ha2 = DigestUtil.md5Hex(method + StrPool.COLON + uri);
            String expectedResponse = DigestUtil.md5Hex(ha1 + StrPool.COLON + nonce + StrPool.COLON + ha2);
            boolean valid = expectedResponse.equals(response);
            if (!valid) {
                log.warn("[authenticate][Digest 校验失败] extension={}, domain={}", extension, domain);
                log.warn("[authenticate][Digest 调试] extension={}, domain={}, password={}, method={}, uri={}, nonce={}, clientResponse={}, expectedResponse={}",
                        extension, domain, agent.getPassword(), method, uri, nonce, response, expectedResponse);
                log.warn("[authenticate][Digest 调试] ha1={}, ha2={}", ha1, ha2);
            }
            return valid;
        } catch (Exception e) {
            log.error("[authenticate][密码验证异常] extension={}", extension, e);
            return false;
        }
    }
}
