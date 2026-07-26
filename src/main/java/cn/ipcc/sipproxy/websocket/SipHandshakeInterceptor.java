package cn.ipcc.sipproxy.websocket;

import cn.ipcc.sipproxy.api.authentication.WsHandshakeAuthenticator;
import cn.ipcc.sipproxy.autoconfigure.SipProxyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.Map;

/**
 * SIP WebSocket 握手拦截器
 * <p>
 * 从原 {@code LoginUserHandshakeInterceptor} 抽取 SIP 子协议协商逻辑。
 * <p>
 * 职责：
 * <ol>
 *   <li>提取 URL 中的 token 参数（按 {@code properties.websocket.tokenQueryParam} 配置）</li>
 *   <li>调用 {@link WsHandshakeAuthenticator} 校验 token（父程序实现的扩展点）</li>
 *   <li>RFC 7118 SIP 子协议协商（响应 {@code Sec-WebSocket-Protocol} 头）</li>
 *   <li>握手通过则将 token 与初始活跃时间写入 attributes，供后续 Handler 使用</li>
 * </ol>
 * <p>
 * 设计约束：sipproxy 不直接依赖父程序鉴权框架（SaToken / Spring Security），
 * 通过 {@code WsHandshakeAuthenticator} 扩展点解耦。
 */
@Slf4j
public class SipHandshakeInterceptor implements HandshakeInterceptor {

    /** RFC 7118 SIP 子协议标识 */
    private static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
    /** RFC 7118 默认 SIP 子协议（明文 SIP over WS） */
    private static final String SIP_SUB_PROTOCOL = "sip";
    /** RFC 7118 加密 SIP 子协议（SIPS over WSS，本项目暂未启用） */
    private static final String SIPS_SUB_PROTOCOL = "sips";

    /** sipproxy 配置属性 */
    private final SipProxyProperties sipProxyProperties;

    /** WS 握手认证扩展点（可选，未提供时跳过 token 校验，仅用于本地调试） */
    private final WsHandshakeAuthenticator wsHandshakeAuthenticator;

    /**
     * 构造握手拦截器
     *
     * @param sipProxyProperties      sipproxy 配置属性
     * @param wsHandshakeAuthenticator WS 握手认证扩展点（可为 null）
     */
    public SipHandshakeInterceptor(SipProxyProperties sipProxyProperties,
                                   WsHandshakeAuthenticator wsHandshakeAuthenticator) {
        this.sipProxyProperties = sipProxyProperties;
        this.wsHandshakeAuthenticator = wsHandshakeAuthenticator;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 1. 提取 token
        String token = extractToken(request);
        // 1.1 require-auth=false 时跳过 token 校验（仅用于本地调试，避免前端必须传 token）
        boolean requireAuth = sipProxyProperties.getWebsocket().isRequireAuth();
        if (token == null) {
            if (requireAuth) {
                log.warn("[beforeHandshake][token 为空且 require-auth=true,拒绝握手]");
                return false;
            }
            // require-auth=false 时，token 为空也允许握手通过（本地调试模式）
            log.debug("[beforeHandshake][token 为空但 require-auth=false,允许握手]");
            // 仍需完成 SIP 子协议协商，否则 JsSIP 客户端会因缺少 Sec-WebSocket-Protocol 响应头立即断开
            handleSipSubProtocol(request, response);
            attributes.put("lastActiveAt", System.currentTimeMillis());
            return true;
        }
        // 2. 调用认证扩展点（父程序实现，未提供时跳过校验）
        if (wsHandshakeAuthenticator != null && requireAuth) {
            String remoteIp = request.getRemoteAddress() != null
                    ? request.getRemoteAddress().getAddress().getHostAddress()
                    : "";
            Map<String, String> headers = new HashMap<>();
            request.getHeaders().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    headers.put(key, values.get(0));
                }
            });
            if (!wsHandshakeAuthenticator.authenticate(token, remoteIp, headers)) {
                log.warn("[beforeHandshake][token 认证失败,拒绝握手] remoteIp={}", remoteIp);
                return false;
            }
        }
        // 3. RFC 7118 SIP 子协议协商：响应 Sec-WebSocket-Protocol 头
        //    缺失会导致 JsSIP 客户端立即断开（EOFException + status=1006）
        handleSipSubProtocol(request, response);
        // 4. 写入 attributes 供后续 Handler 使用
        attributes.put("token", token);
        attributes.put("lastActiveAt", System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手后无需处理（RFC 7118 子协议协商在 beforeHandshake 完成）
    }

    /**
     * RFC 7118 SIP 子协议协商
     * <p>
     * 业务背景：JsSIP 客户端握手时发送 {@code Sec-WebSocket-Protocol: sip} 头，
     * 服务端必须返回所选子协议，否则浏览器会立即关闭 WebSocket 连接
     * （表现为 {@code java.io.EOFException} + {@code CloseStatus[code=1006]}）。
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>读取客户端请求的所有子协议（逗号分隔）</li>
     *   <li>优先匹配 {@code sip}，其次 {@code sips}（与原 LoginUserHandshakeInterceptor 保持一致）</li>
     *   <li>命中则在响应头追加所选子协议</li>
     * </ol>
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     */
    private void handleSipSubProtocol(ServerHttpRequest request, ServerHttpResponse response) {
        String clientProtocols = request.getHeaders().getFirst(SEC_WEBSOCKET_PROTOCOL);
        if (clientProtocols == null || clientProtocols.isEmpty()) {
            return;
        }
        for (String protocol : clientProtocols.split(",")) {
            String trimmed = protocol.trim();
            if (SIP_SUB_PROTOCOL.equalsIgnoreCase(trimmed)
                    || SIPS_SUB_PROTOCOL.equalsIgnoreCase(trimmed)) {
                // RFC 7118：服务端返回所选子协议（默认 sip）
                response.getHeaders().add(SEC_WEBSOCKET_PROTOCOL, SIP_SUB_PROTOCOL);
                return;
            }
        }
    }

    /**
     * 从请求中提取 token
     * <p>
     * 优先从 ServletRequest.getParameter 提取（已 URL 解码），
     * 兜底从 URI query 字符串解析（非 Servlet 环境兼容）。
     *
     * @param request HTTP 请求
     * @return token 字符串，未找到返回 null
     */
    private String extractToken(ServerHttpRequest request) {
        String tokenParam = sipProxyProperties.getWebsocket().getTokenQueryParam();
        if (request instanceof ServletServerHttpRequest servletRequest) {
            return servletRequest.getServletRequest().getParameter(tokenParam);
        }
        // 非 Servlet 环境兜底：从 URI query 解析
        String query = request.getURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(tokenParam)) {
                return kv[1];
            }
        }
        return null;
    }
}
