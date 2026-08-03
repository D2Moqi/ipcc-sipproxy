package cn.ipcc.sipproxy.defaults.trace;

import cn.ipcc.sipproxy.api.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 链路追踪默认实现（ThreadLocal）
 * <p>
 * 设计意图：父程序未实现 {@link TraceContext} 时的兜底实现，
 * 使用 ThreadLocal 存储 traceId，仅在本进程内有效，满足基本日志串联需求。
 * <p>
 * 父程序实现 {@link TraceContext} 接口并注册为 Bean 即可覆盖此默认实现，
 * 将 sipproxy 的链路追踪集成到父程序的追踪体系（如 SkyWalking、Zipkin）。
 *
 * @author ipcc
 */
@Slf4j
public class DefaultTraceContext implements TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    @Override
    public void setTraceId(String callId) {
        TRACE_ID.set(callId);
    }

    @Override
    public String getTraceId() {
        return TRACE_ID.get();
    }
}
