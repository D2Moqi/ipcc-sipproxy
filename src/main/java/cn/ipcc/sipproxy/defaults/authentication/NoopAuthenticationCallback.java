package cn.ipcc.sipproxy.defaults.authentication;

import cn.ipcc.sipproxy.api.authentication.AuthenticationCallback;
import lombok.extern.slf4j.Slf4j;

/**
 * 认证事件回调默认实现（空操作 + 日志记录）
 * <p>
 * 设计意图：当父程序未实现 {@link AuthenticationCallback} 时作为兜底实现，
 * 记录认证成功/失败日志，便于运维排查认证异常与发现暴力破解。
 * <p>
 * 父程序若需在认证成功/失败时执行额外逻辑（如更新坐席在线状态、
 * 触发签入流程、记录审计日志），实现 {@link AuthenticationCallback} 接口
 * 注册为 Bean 即可覆盖本实现。
 *
 * @author ipcc
 */
@Slf4j
public class NoopAuthenticationCallback implements AuthenticationCallback {

    /**
     * 认证成功回调：记录 INFO 日志
     *
     * @param extension 分机号
     * @param domain    域名
     * @param sessionId WebSocket 会话 ID
     */
    @Override
    public void onSuccess(String extension, String domain, String sessionId) {
        log.info("[onSuccess][认证成功] extension={}, domain={}, sessionId={}", extension, domain, sessionId);
    }

    /**
     * 认证失败回调：记录 WARN 日志，便于发现暴力破解
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
