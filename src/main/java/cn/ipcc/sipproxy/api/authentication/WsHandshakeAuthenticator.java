package cn.ipcc.sipproxy.api.authentication;

import java.util.Map;

/**
 * WS 握手认证扩展点
 * <p>
 * 父程序实现该接口，为 sipproxy 提供 WebSocket 握手阶段的 token 认证能力。
 * Token 由前端通过 URL 参数 {@code ?token=xxx} 传递，sipproxy 在握手阶段调用此接口完成校验，
 * 不直接依赖父程序的鉴权框架（如 SaToken / Spring Security）。
 */
public interface WsHandshakeAuthenticator {

    /**
     * 校验握手 token
     *
     * @param token          token 字符串（从 URL query 参数提取）
     * @param remoteIp       客户端 IP（用于风控审计）
     * @param requestHeaders HTTP 握手请求头（含 Cookie、User-Agent 等）
     * @return 认证通过返回 true，失败返回 false
     */
    boolean authenticate(String token, String remoteIp, Map<String, String> requestHeaders);
}
