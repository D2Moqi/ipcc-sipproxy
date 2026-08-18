package cn.ipcc.sipproxy.api.authentication;

/**
 * 认证事件回调扩展点（可选实现）
 * <p>
 * 父程序可实现该接口，在认证成功/失败时执行额外逻辑（如记录审计日志、更新坐席在线状态、
 * 触发坐席签入流程等）。sipproxy 在认证流程结束后异步回调，不阻塞主流程。
 * <p>
 * 若父程序未提供实现，sipproxy 将跳过回调，不影响认证主流程。
 */
public interface AuthenticationCallback {

    /**
     * 认证成功回调
     *
     * @param extension 分机号
     * @param domain    域名
     * @param sessionId WebSocket 会话 ID（用于关联坐席与 WS 连接）
     */
    void onSuccess(String extension, String domain, String sessionId);

    /**
     * 认证失败回调
     *
     * @param extension 分机号（可能为 null，表示解析失败）
     * @param domain    域名（可能为 null）
     * @param reason    失败原因（如 "密码错误"、"坐席不存在"）
     */
    void onFailure(String extension, String domain, String reason);

    /**
     * 重复登录检查回调（在发送 200 OK 之前调用）
     * <p>
     * 当同一坐席已有活跃会话时，sipproxy 在发送 200 OK 之前调用此方法，
     * 由父程序决定是否允许新登录（如强制踢下线旧会话）。
     * <p>
     * 默认实现（NoopAuthenticationCallback）直接返回 true，允许重复登录。
     *
     * @param extension         分机号
     * @param domain            域名
     * @param existingSessionId 已存在的 WebSocket 会话 ID
     * @param newSessionId      新请求的 WebSocket 会话 ID
     * @return true 允许新登录（旧会话将被清理），false 拒绝新登录
     */
    default boolean onDuplicateLogin(String extension, String domain, String existingSessionId, String newSessionId) {
        return true;
    }
}
