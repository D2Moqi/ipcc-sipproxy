package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.authentication.AuthenticationCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 认证事件回调扩展点自定义实现（替代 {@code NoopAuthenticationCallback}）。
 * <p>
 * 用途：在认证成功/失败时执行额外逻辑（如记录审计日志、更新坐席在线状态、触发坐席签入流程）。
 * sipproxy 在认证流程结束后异步回调，不阻塞主流程。
 * <p>
 * 数据来源：无（仅记录日志，不持久化）。
 * <p>
 * 与默认实现的差异：
 * <ul>
 *   <li>默认实现 {@code NoopAuthenticationCallback} 为空实现（不记录任何日志）；</li>
 *   <li>本实现记录认证成功/失败日志，便于演示认证回调扩展点的集成与审计场景。</li>
 * </ul>
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomAuthenticationCallback implements AuthenticationCallback {

    /**
     * 认证成功回调（记录审计日志）。
     *
     * @param extension 分机号
     * @param domain    域名
     * @param sessionId WebSocket 会话 ID（用于关联坐席与 WS 连接）
     */
    @Override
    public void onSuccess(String extension, String domain, String sessionId) {
        log.info("[onSuccess][认证成功] extension={}, domain={}, sessionId={}", extension, domain, sessionId);
    }

    /**
     * 认证失败回调（记录审计日志）。
     *
     * @param extension 分机号（可能为 null，表示解析失败）
     * @param domain    域名（可能为 null）
     * @param reason    失败原因（如 "密码错误"、"坐席不存在"）
     */
    @Override
    public void onFailure(String extension, String domain, String reason) {
        log.warn("[onFailure][认证失败] extension={}, domain={}, reason={}", extension, domain, reason);
    }
}
