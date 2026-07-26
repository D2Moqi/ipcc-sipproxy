package cn.ipcc.sipproxy.autoconfigure;

import cn.ipcc.sipproxy.api.authentication.WsHandshakeAuthenticator;
import cn.ipcc.sipproxy.core.SipProxyService;
import cn.ipcc.sipproxy.websocket.LocalWsSessionManager;
import cn.ipcc.sipproxy.websocket.SipFrameReassembler;
import cn.ipcc.sipproxy.websocket.SipHandshakeInterceptor;
import cn.ipcc.sipproxy.websocket.SipWebSocketHandler;
import cn.ipcc.sipproxy.websocket.WsSessionManager;
import cn.ipcc.sipproxy.websocket.ZombieSessionCleaner;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * sipproxy WebSocket 自动配置
 * <p>
 * 设计意图：配置 JSR-356 标准 WebSocket 容器与 SIP over WebSocket 接入链路。
 * <ul>
 *   <li>{@code maxTextMessageBufferSize}：限制单条 SIP 消息最大 8KB，防止恶意超大消息撑爆内存</li>
 *   <li>{@code maxSessionIdleTimeout}：与 {@link SipProxyProperties.Heartbeat#idleTimeout} 对齐，
 *       超时未活动的 WS 会话由容器自动关闭，配合僵尸清理任务保证资源释放</li>
 *   <li>注册 {@link SipWebSocketHandler} 到 {@code sipproxy.websocket.path} 端点，
 *       配合 {@link SipHandshakeInterceptor} 完成 token 校验</li>
 * </ul>
 * <p>
 * 触发条件：{@code sipproxy.websocket.enabled=true}（默认启用）。
 * <p>
 * Bean 注册策略：
 * <ul>
 *   <li>基础设施 Bean（容器、重组器、会话管理器、握手拦截器）：无条件注册</li>
 *   <li>依赖 {@link SipProxyService} 的 Bean（Handler、ZombieSessionCleaner）：
 *       通过 {@code @ConditionalOnBean(SipProxyService.class)} 保护，确保 SipProxyService
 *       未实现时不会导致容器启动失败（编译期不需要，运行期需要）</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "sipproxy.websocket", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableWebSocket
public class SipProxyWebSocketAutoConfiguration {

    @Resource
    private SipProxyProperties properties;

    /**
     * 配置 JSR-356 WebSocket 容器
     * <p>
     * 设计依据：
     * <ul>
     *   <li>8KB 文本缓冲：覆盖 JsSIP 单条 SIP 消息（典型 1-3KB）的上限，预留 SDP 扩展空间</li>
     *   <li>idleTimeout 转换为毫秒：JSR-356 API 单位为毫秒，配置项单位为秒</li>
     * </ul>
     *
     * @return ServletServerContainerFactoryBean 用于创建 WS 容器
     */
    @Bean
    public ServletServerContainerFactoryBean sipProxyWebSocketContainer() {
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
        // 8KB 文本消息缓冲上限
        factory.setMaxTextMessageBufferSize(8 * 1024);
        // 心跳超时（秒→毫秒）：与配置对齐，超时由容器自动关闭连接
        Integer idleTimeout = properties.getHeartbeat().getIdleTimeout();
        if (idleTimeout != null) {
            factory.setMaxSessionIdleTimeout(idleTimeout * 1000L);
        }
        return factory;
    }

    /**
     * 注册 SIP 消息分片重组器
     *
     * @return SipFrameReassembler 实例
     */
    @Bean
    public SipFrameReassembler sipFrameReassembler() {
        return new SipFrameReassembler();
    }

    /**
     * 注册本地 WebSocket 会话管理器（默认实现）
     * <p>
     * 通过 {@code @ConditionalOnMissingBean} 允许父程序覆盖为 Redis 实现以支持多实例。
     *
     * @return LocalWsSessionManager 实例
     */
    @Bean
    @ConditionalOnMissingBean(WsSessionManager.class)
    public LocalWsSessionManager localWsSessionManager() {
        return new LocalWsSessionManager();
    }

    /**
     * 注册 SIP WebSocket 握手拦截器
     * <p>
     * WsHandshakeAuthenticator 为可选依赖（@Autowired required=false），
     * 未提供时跳过 token 校验，仅用于本地调试。
     *
     * @param properties      sipproxy 配置属性
     * @param authenticator   WS 握手认证扩展点（可为 null）
     * @return SipHandshakeInterceptor 实例
     */
    @Bean
    public SipHandshakeInterceptor sipHandshakeInterceptor(SipProxyProperties properties,
                                                           @Autowired(required = false) WsHandshakeAuthenticator authenticator) {
        return new SipHandshakeInterceptor(properties, authenticator);
    }

    /**
     * 注册 WebSocketConfigurer，将 SipWebSocketHandler 绑定到 {@code sipproxy.websocket.path} 端点
     * <p>
     * 通过 {@link ObjectProvider} 延迟获取 SipWebSocketHandler，确保 SipProxyService 未提供时
     * 不会导致容器启动失败（仅跳过 Handler 注册）。
     *
     * @param handlerProvider     SipWebSocketHandler Bean 提供者
     * @param interceptorProvider SipHandshakeInterceptor Bean 提供者
     * @param properties          sipproxy 配置属性
     * @return WebSocketConfigurer 实例
     */
    @Bean
    public WebSocketConfigurer sipProxyWebSocketConfigurer(ObjectProvider<SipWebSocketHandler> handlerProvider,
                                                           ObjectProvider<SipHandshakeInterceptor> interceptorProvider,
                                                           SipProxyProperties properties) {
        return registry -> {
            SipWebSocketHandler handler = handlerProvider.getIfAvailable();
            // SipProxyService 未提供时跳过端点注册，避免启动失败
            if (handler == null) {
                return;
            }
            SipHandshakeInterceptor interceptor = interceptorProvider.getIfAvailable();
            var registration = registry.addHandler(handler, properties.getWebsocket().getPath())
                    .setAllowedOriginPatterns("*");
            if (interceptor != null) {
                registration.addInterceptors(interceptor);
            }
        };
    }

    /**
     * 依赖 SipProxyService 的 Bean 注册
     * <p>
     * 内部配置类，仅当容器中存在 {@link SipProxyService} Bean 时生效。
     * 避免 SipProxyService 未实现时导致 SipWebSocketHandler / ZombieSessionCleaner 注入失败。
     */
    @Configuration
    @ConditionalOnBean(SipProxyService.class)
    public static class SipProxyServiceDependentConfiguration {

        /**
         * 注册 SIP WebSocket 消息处理器
         *
         * @param sipProxyService     sipproxy 核心服务
         * @param wsSessionManager    WebSocket 会话管理器
         * @param sipFrameReassembler SIP 消息分片重组器
         * @return SipWebSocketHandler 实例
         */
        @Bean
        public SipWebSocketHandler sipWebSocketHandler(SipProxyService sipProxyService,
                                                       WsSessionManager wsSessionManager,
                                                       SipFrameReassembler sipFrameReassembler) {
            return new SipWebSocketHandler(sipProxyService, wsSessionManager, sipFrameReassembler);
        }

        /**
         * 注册僵尸 WebSocket 会话清理任务
         * <p>
         * 启用条件：{@code sipproxy.heartbeat.zombie-clean-enabled=true}（默认启用）。
         * 需配合 {@code @EnableScheduling}（在 {@link SipProxyClusterAutoConfiguration} 启用）。
         *
         * @param properties       sipproxy 配置属性
         * @param wsSessionManager WebSocket 会话管理器
         * @param sipProxyService  sipproxy 核心服务
         * @return ZombieSessionCleaner 实例
         */
        @Bean
        @ConditionalOnProperty(prefix = "sipproxy.heartbeat", name = "zombie-clean-enabled",
                havingValue = "true", matchIfMissing = true)
        public ZombieSessionCleaner zombieSessionCleaner(SipProxyProperties properties,
                                                         WsSessionManager wsSessionManager,
                                                         SipProxyService sipProxyService) {
            return new ZombieSessionCleaner(properties, wsSessionManager, sipProxyService);
        }
    }
}
