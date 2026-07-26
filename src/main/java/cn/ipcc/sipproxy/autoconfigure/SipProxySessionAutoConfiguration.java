package cn.ipcc.sipproxy.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * sipproxy 会话存储自动配置
 * <p>
 * 设计意图：配置会话存储（SessionRepository）的默认实现。
 * 默认使用 InMemorySessionRepository（单实例），生产环境可通过自定义 Bean 替换为 Redis 实现。
 * <p>
 * 当前为骨架实现；后续迁移阶段将注册 InMemorySessionRepository 兜底 Bean，
 * 并通过 @ConditionalOnMissingBean 允许父程序覆盖。
 */
@AutoConfiguration
public class SipProxySessionAutoConfiguration {

    // 骨架实现：后续注册 InMemorySessionRepository 兜底 Bean
    // 通过 @ConditionalOnMissingBean 允许父程序提供 Redis 实现覆盖
}
