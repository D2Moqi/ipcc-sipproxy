package cn.ipcc.sipproxy.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * sipproxy 模块主自动配置类
 * <p>
 * 设计意图：作为 Spring Boot Starter 的入口，通过 {@link EnableConfigurationProperties}
 * 启用 {@link SipProxyProperties} 配置属性绑定。
 * <p>
 * 当前为骨架实现，仅完成属性绑定；后续将在此注册 SipProxyService、SipNodeManager 等核心 Bean。
 * <p>
 * 通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 自动注册，无需父程序显式 @Import。
 */
@AutoConfiguration
@EnableConfigurationProperties(SipProxyProperties.class)
public class SipProxyAutoConfiguration {

    // 骨架实现：仅注入 SipProxyProperties，核心 Bean 注册将在后续迁移阶段补充
}
