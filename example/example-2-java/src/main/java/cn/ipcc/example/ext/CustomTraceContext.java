package cn.ipcc.example.ext;

import cn.ipcc.sipproxy.api.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 链路追踪扩展点自定义实现（替代 {@code DefaultTraceContext}）。
 * <p>
 * 用途：将 sipproxy 的链路追踪集成到父程序的追踪体系（如 SkyWalking、Zipkin）。
 * <p>
 * 数据来源：无（使用 ThreadLocal 存储 traceId，仅在本进程内有效）。
 * <p>
 * 与默认实现的差异：
 * <ul>
 *   <li>默认实现 {@code DefaultTraceContext} 同样使用 ThreadLocal 存储 traceId；</li>
 *   <li>本实现行为与默认一致，但显式标注 {@code @Component} 注册为 Bean，覆盖默认的 {@code @ConditionalOnMissingBean} 实现，
 *       演示父程序接管追踪扩展点的集成方式。父程序可在此基础上替换为对接 SkyWalking/Zipkin 的实现。</li>
 * </ul>
 *
 * @author ipcc
 */
@Slf4j
@Component
public class CustomTraceContext implements TraceContext {

    /** ThreadLocal 存储 traceId（仅本进程内有效，跨线程不传递） */
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    /**
     * 设置 traceId。
     *
     * @param callId 呼叫 ID（SIP Call-ID 头，作为 traceId 关联同一通话的全程信令）
     */
    @Override
    public void setTraceId(String callId) {
        TRACE_ID.set(callId);
    }

    /**
     * 获取当前 traceId。
     *
     * @return traceId（未设置时返回 null）
     */
    @Override
    public String getTraceId() {
        return TRACE_ID.get();
    }
}
