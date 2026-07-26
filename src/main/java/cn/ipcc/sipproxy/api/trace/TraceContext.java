package cn.ipcc.sipproxy.api.trace;

/**
 * 链路追踪扩展点（可选实现）
 * <p>
 * 父程序可实现该接口，将 sipproxy 的链路追踪集成到父程序的追踪体系（如 SkyWalking、Zipkin）。
 * 默认实现使用 ThreadLocal 存储 traceId，仅在本进程内有效。
 * <p>
 * 设计约束：sipproxy 不直接依赖任何 APM 框架，通过此接口与父程序的追踪体系解耦。
 */
public interface TraceContext {

    /**
     * 设置 traceId
     *
     * @param callId 呼叫 ID（SIP Call-ID 头，作为 traceId 关联同一通话的全程信令）
     */
    void setTraceId(String callId);

    /**
     * 获取当前 traceId
     *
     * @return traceId（未设置时返回 null）
     */
    String getTraceId();
}
