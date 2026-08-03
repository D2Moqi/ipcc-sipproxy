package cn.ipcc.sipproxy.defaults.authentication;

import cn.ipcc.sipproxy.api.authentication.WsHandshakeAuthenticator;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * WebSocket 握手认证默认实现（全部放行）
 * <p>
 * 设计意图：父程序未实现 {@link WsHandshakeAuthenticator} 时的兜底实现，
 * 始终返回 true 表示不校验 token，适用于内网部署无安全要求的场景。
 * <p>
 * 父程序实现 {@link WsHandshakeAuthenticator} 接口并注册为 Bean 即可覆盖此默认实现，
 * 为 sipproxy 提供 WebSocket 握手阶段的 token 认证能力。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultWsHandshakeAuthenticator implements WsHandshakeAuthenticator {

    @Override
    public boolean authenticate(String token, String remoteIp, Map<String, String> requestHeaders) {
        log.debug("[authenticate][默认实现全部放行，父程序未提供WS握手认证] remoteIp={}", remoteIp);
        return true;
    }
}
