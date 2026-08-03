package cn.ipcc.example.ext;

import cn.hutool.core.text.StrPool;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.ipcc.sipproxy.api.agent.AgentInfoProvider;
import cn.ipcc.sipproxy.api.authentication.SipAuthenticationProvider;
import cn.ipcc.sipproxy.support.model.AgentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SIP Digest 认证扩展点自定义实现（替代 {@code DefaultSipAuthenticationProvider}）。
 * <p>
 * 用途：完全接管 sipproxy 的 SIP Digest 认证校验，适用于父程序已有完整认证体系的场景。
 * <p>
 * 数据来源：通过构造注入的 {@link AgentInfoProvider}（即 {@link CustomAgentInfoProvider}）获取坐席密码，
 * 硬编码坐席密码为 123456。
 * <p>
 * 与默认实现的差异：
 * <ul>
 *   <li>默认实现 {@code DefaultSipAuthenticationProvider} 的 Digest 计算逻辑完全一致（HA1/HA2/response 三步 MD5）；</li>
 *   <li>本实现显式标注 {@code @Component} 注册为 Bean，覆盖默认的 {@code @ConditionalOnMissingBean} 实现，
 *       演示父程序接管认证扩展点的集成方式。父程序可在此基础上替换为对接外部鉴权系统（RADIUS 等）。</li>
 * </ul>
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomSipAuthenticationProvider implements SipAuthenticationProvider {

    private final AgentInfoProvider agentInfoProvider;

    /**
     * 构造方法注入坐席信息查询扩展点。
     *
     * @param agentInfoProvider 坐席信息查询扩展点（用于获取坐席密码进行 Digest 计算）
     */
    public CustomSipAuthenticationProvider(AgentInfoProvider agentInfoProvider) {
        this.agentInfoProvider = agentInfoProvider;
    }

    /**
     * 本地 Digest 校验（标准 RFC 2617 算法）。
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>通过 AgentInfoProvider 查询坐席信息（含密码，硬编码 123456）</li>
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
            // 标准 Digest 算法：HA1 = MD5(username:realm:password)
            String ha1 = DigestUtil.md5Hex(extension + StrPool.COLON + domain + StrPool.COLON + agent.getPassword());
            // HA2 = MD5(method:uri)
            String ha2 = DigestUtil.md5Hex(method + StrPool.COLON + uri);
            // expectedResponse = MD5(HA1:nonce:HA2)
            String expectedResponse = DigestUtil.md5Hex(ha1 + StrPool.COLON + nonce + StrPool.COLON + ha2);
            boolean valid = expectedResponse.equals(response);
            if (!valid) {
                log.warn("[authenticate][Digest 校验失败] extension={}, domain={}", extension, domain);
            }
            return valid;
        } catch (Exception e) {
            log.error("[authenticate][密码验证异常] extension={}", extension, e);
            return false;
        }
    }
}
