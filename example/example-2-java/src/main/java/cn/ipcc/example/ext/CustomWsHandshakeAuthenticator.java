package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.authentication.WsHandshakeAuthenticator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WebSocket 握手认证扩展点自定义实现（替代 {@code DefaultWsHandshakeAuthenticator}）。
 * <p>
 * 用途：为 sipproxy 提供 WebSocket 握手阶段的 token 认证能力。
 * Token 由前端通过 URL 参数 {@code ?token=xxx} 传递，sipproxy 在握手阶段调用此接口完成校验。
 * <p>
 * 数据来源：硬编码固定 token（不依赖外部鉴权框架）。
 * <ul>
 *   <li>有效 token：{@code test-token}</li>
 * </ul>
 * <p>
 * 与默认实现的差异：
 * <ul>
 *   <li>默认实现 {@code DefaultWsHandshakeAuthenticator} 基于 H2 seed 数据查询坐席 token；</li>
 *   <li>本实现直接比对硬编码 token，去除数据库依赖，便于本地联调。</li>
 * </ul>
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomWsHandshakeAuthenticator implements WsHandshakeAuthenticator {

    /** 硬编码有效 token（演示用，前端握手时通过 ?token=test-token 传递） */
    private static final String VALID_TOKEN = "test-token";

    /**
     * 校验握手 token（硬编码匹配）。
     *
     * @param token          token 字符串（从 URL query 参数提取）
     * @param remoteIp       客户端 IP（用于风控审计，本实现仅记录日志）
     * @param requestHeaders HTTP 握手请求头（含 Cookie、User-Agent 等，本实现仅记录日志）
     * @return token="test-token" 时返回 true，否则返回 false
     */
    @Override
    public boolean authenticate(String token, String remoteIp, Map<String, String> requestHeaders) {
        boolean valid = VALID_TOKEN.equals(token);
        if (valid) {
            log.info("[authenticate][WS 握手认证成功] remoteIp={}", remoteIp);
        } else {
            log.warn("[authenticate][WS 握手认证失败] remoteIp={}, token={}", remoteIp, token);
        }
        return valid;
    }
}
