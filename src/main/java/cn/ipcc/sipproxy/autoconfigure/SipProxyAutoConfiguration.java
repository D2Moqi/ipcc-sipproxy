package cn.ipcc.sipproxy.autoconfigure;

import cn.ipcc.sipproxy.api.agent.AgentInfoProvider;
import cn.ipcc.sipproxy.api.authentication.AuthenticationCallback;
import cn.ipcc.sipproxy.api.authentication.SipAuthenticationProvider;
import cn.ipcc.sipproxy.api.authentication.WsHandshakeAuthenticator;
import cn.ipcc.sipproxy.api.fs.FsNodeProvider;
import cn.ipcc.sipproxy.api.gateway.GatewayProvider;
import cn.ipcc.sipproxy.api.gateway.MessageSourceIdentifier;
import cn.ipcc.sipproxy.api.gateway.OutboundGatewayRewriter;
import cn.ipcc.sipproxy.api.interceptor.SipMessageInterceptor;
import cn.ipcc.sipproxy.api.media.SdpProcessor;
import cn.ipcc.sipproxy.api.security.IpWhitelist;
import cn.ipcc.sipproxy.api.security.SipRateLimiter;
import cn.ipcc.sipproxy.api.trace.TraceContext;
import cn.ipcc.sipproxy.api.transport.SipMessageTransport;
import cn.ipcc.sipproxy.defaults.agent.DefaultAgentInfoProvider;
import cn.ipcc.sipproxy.defaults.authentication.DefaultSipAuthenticationProvider;
import cn.ipcc.sipproxy.defaults.authentication.DefaultWsHandshakeAuthenticator;
import cn.ipcc.sipproxy.defaults.authentication.NoopAuthenticationCallback;
import cn.ipcc.sipproxy.defaults.fs.DefaultFsNodeProvider;
import cn.ipcc.sipproxy.defaults.gateway.DefaultGatewayProvider;
import cn.ipcc.sipproxy.defaults.gateway.DefaultMessageSourceIdentifier;
import cn.ipcc.sipproxy.defaults.gateway.DefaultOutboundGatewayRewriter;
import cn.ipcc.sipproxy.defaults.interceptor.NoopSipMessageInterceptor;
import cn.ipcc.sipproxy.defaults.media.DefaultSdpProcessor;
import cn.ipcc.sipproxy.defaults.security.DefaultIpWhitelist;
import cn.ipcc.sipproxy.defaults.security.DefaultSipRateLimiter;
import cn.ipcc.sipproxy.defaults.trace.DefaultTraceContext;
import cn.ipcc.sipproxy.defaults.transport.DefaultSipMessageTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * sipproxy 模块主自动配置类
 * <p>
 * 设计意图：作为 Spring Boot Starter 的入口，统一注册 sipproxy 模块所有扩展点的默认实现。
 * <p>
 * 核心服务（{@code SipProxyService}、{@code SipMessageForwarder}、{@code SipSessionManager}、
 * {@code SipNodeManager}、{@code GatewayAuthManager}、各 handler 工厂等）通过 {@code @Service}/
 * {@code @Component} 注解由 Spring 容器自动扫描注册，本配置类仅负责扩展点默认实现的条件化注册。
 * <p>
 * 扩展点 Bean 注册策略：
 * <ul>
 *   <li>所有默认实现均通过 {@code @Bean} + {@code @ConditionalOnMissingBean} 注册</li>
 *   <li>父程序实现对应扩展点接口并注册为 {@code @Component} 即可覆盖默认实现</li>
 *   <li>覆盖关系由 Spring 容器在 Bean 注册阶段自动判定，无需显式配置</li>
 * </ul>
 * <p>
 * 架构原则：{@code api/} 包下定义的所有扩展点接口，在 {@code defaults/} 包下均有对应的默认实现，
 * 保证 sipproxy 可独立启动（功能受限但不会因缺少 Bean 定义而启动失败）。
 * <p>
 * H2 数据支持：坐席/FS 节点/网关三个默认实现通过 {@link ObjectProvider} 可选注入 {@link JdbcTemplate}，
 * 父程序配置数据源（含 H2 内存库）时自动查询 seed 数据，未配置时退化为 null/空列表，保持向后兼容。
 * <p>
 * 通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 自动注册，无需父程序显式 {@code @Import}。
 *
 * @author ipcc
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(SipProxyProperties.class)
@ConditionalOnProperty(prefix = "sipproxy", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "cn.ipcc.sipproxy")
public class SipProxyAutoConfiguration {

    // ==================== 坐席扩展点默认实现 ====================

    /**
     * 注册坐席信息查询默认实现
     * <p>
     * 启用条件：容器中不存在 {@link AgentInfoProvider} Bean 时注册。
     * 默认实现在存在 {@link JdbcTemplate} 时查询 H2 seed 数据表 sip_agent，否则返回 null（无坐席信息），
     * sipproxy 注册认证和呼叫路由功能在无数据源时受限。
     *
     * @param jdbcTemplateProvider 可选的 JdbcTemplate，父程序未配置数据源时为 null
     * @return DefaultAgentInfoProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(AgentInfoProvider.class)
    public AgentInfoProvider defaultAgentInfoProvider(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        log.info("[defaultAgentInfoProvider][注册默认坐席信息查询（H2 查询或返回null）]");
        return new DefaultAgentInfoProvider(jdbcTemplateProvider.getIfAvailable());
    }

    // ==================== FS 节点扩展点默认实现 ====================

    /**
     * 注册 FS 节点查询默认实现
     * <p>
     * 启用条件：容器中不存在 {@link FsNodeProvider} Bean 时注册。
     * 默认实现在存在 {@link JdbcTemplate} 时查询 H2 seed 数据表 sip_fs_node 返回启用节点，
     * 否则返回空列表（无 FS 节点），sipproxy 呼叫功能在无数据源时不可用但服务可启动。
     *
     * @param jdbcTemplateProvider 可选的 JdbcTemplate，父程序未配置数据源时为 null
     * @return DefaultFsNodeProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(FsNodeProvider.class)
    public FsNodeProvider defaultFsNodeProvider(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        log.info("[defaultFsNodeProvider][注册默认FS节点查询（H2 查询或空列表）]");
        return new DefaultFsNodeProvider(jdbcTemplateProvider.getIfAvailable());
    }

    // ==================== 网关扩展点默认实现 ====================

    /**
     * 注册网关查询默认实现
     * <p>
     * 启用条件：容器中不存在 {@link GatewayProvider} Bean 时注册。
     * 默认实现在存在 {@link JdbcTemplate} 时查询 H2 seed 数据表 sip_gateway，
     * 否则返回空列表/null（无网关配置），出局呼叫和来源识别功能在无数据源时受限。
     *
     * @param jdbcTemplateProvider 可选的 JdbcTemplate，父程序未配置数据源时为 null
     * @return DefaultGatewayProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(GatewayProvider.class)
    public GatewayProvider defaultGatewayProvider(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        log.info("[defaultGatewayProvider][注册默认网关查询（H2 查询或空列表）]");
        return new DefaultGatewayProvider(jdbcTemplateProvider.getIfAvailable());
    }

    /**
     * 注册消息来源识别默认实现
     * <p>
     * 启用条件：容器中不存在 {@link MessageSourceIdentifier} Bean 时注册。
     * 默认实现基于"坐席记录查询（优先）+ User-Agent（兜底）+ Via IP"区分 WEBSOCKET / FREESWITCH / THIRD_PARTY 三类来源。
     * 坐席来源判断优先基于 From 头 extension+domain 查询坐席记录是否存在，避免仅依赖 JsSIP UA 漏判普通 SIP 坐席客户端。
     *
     * @param fsNodeProvider    FS 节点查询扩展点（用于匹配 FREESWITCH 来源）
     * @param gatewayProvider   网关查询扩展点（用于匹配 THIRD_PARTY 来源）
     * @param agentInfoProvider 坐席信息查询扩展点（用于匹配坐席来源 WEBSOCKET）
     * @return DefaultMessageSourceIdentifier 实例
     */
    @Bean
    @ConditionalOnMissingBean(MessageSourceIdentifier.class)
    public MessageSourceIdentifier defaultMessageSourceIdentifier(FsNodeProvider fsNodeProvider,
                                                                   GatewayProvider gatewayProvider,
                                                                   AgentInfoProvider agentInfoProvider) {
        log.info("[defaultMessageSourceIdentifier][注册默认消息来源识别器]");
        return new DefaultMessageSourceIdentifier(fsNodeProvider, gatewayProvider, agentInfoProvider);
    }

    /**
     * 注册出局信令改写默认实现
     * <p>
     * 启用条件：容器中不存在 {@link OutboundGatewayRewriter} Bean 时注册。
     * 默认实现执行标准 3 步改写（From 头、P-Asserted-Identity、Record-Route 清理）。
     *
     * @return DefaultOutboundGatewayRewriter 实例
     * @throws Exception JAIN-SIP 工厂初始化失败
     */
    @Bean
    @ConditionalOnMissingBean(OutboundGatewayRewriter.class)
    public OutboundGatewayRewriter defaultOutboundGatewayRewriter() throws Exception {
        log.info("[defaultOutboundGatewayRewriter][注册默认出局信令改写器]");
        return new DefaultOutboundGatewayRewriter();
    }

    // ==================== 媒体扩展点默认实现 ====================

    /**
     * 注册 SDP 处理默认实现
     * <p>
     * 启用条件：容器中不存在 {@link SdpProcessor} Bean 时注册。
     * 默认实现为透传，不做任何 SDP 修改。
     *
     * @return DefaultSdpProcessor 实例
     */
    @Bean
    @ConditionalOnMissingBean(SdpProcessor.class)
    public SdpProcessor defaultSdpProcessor() {
        log.info("[defaultSdpProcessor][注册默认SDP处理器（透传）]");
        return new DefaultSdpProcessor();
    }

    // ==================== 安全扩展点默认实现 ====================

    /**
     * 注册 IP 白名单默认实现
     * <p>
     * 启用条件：容器中不存在 {@link IpWhitelist} Bean 时注册。
     * 默认实现全部放行，适用于内网部署无第三方网关接入的场景。
     *
     * @return DefaultIpWhitelist 实例
     */
    @Bean
    @ConditionalOnMissingBean(IpWhitelist.class)
    public IpWhitelist defaultIpWhitelist() {
        log.info("[defaultIpWhitelist][注册默认IP白名单（全部放行）]");
        return new DefaultIpWhitelist();
    }

    /**
     * 注册 SIP 速率限制默认实现
     * <p>
     * 启用条件：容器中不存在 {@link SipRateLimiter} Bean 时注册。
     * 默认实现不做限制，全部放行。
     *
     * @return DefaultSipRateLimiter 实例
     */
    @Bean
    @ConditionalOnMissingBean(SipRateLimiter.class)
    public SipRateLimiter defaultSipRateLimiter() {
        log.info("[defaultSipRateLimiter][注册默认SIP限流器（全部放行）]");
        return new DefaultSipRateLimiter();
    }

    // ==================== 认证扩展点默认实现 ====================

    /**
     * 注册认证事件回调默认实现
     * <p>
     * 启用条件：容器中不存在 {@link AuthenticationCallback} Bean 时注册。
     * 默认实现 {@link NoopAuthenticationCallback} 记录认证成功/失败日志，
     * 便于运维排查认证异常与发现暴力破解。
     * <p>
     * 父程序若需在认证成功/失败时执行额外逻辑（如更新坐席在线状态、
     * 触发签入流程、记录审计日志），实现 {@link AuthenticationCallback}
     * 接口注册为 Bean 即可覆盖。
     *
     * @return NoopAuthenticationCallback 实例
     */
    @Bean
    @ConditionalOnMissingBean(AuthenticationCallback.class)
    public AuthenticationCallback defaultAuthenticationCallback() {
        log.info("[defaultAuthenticationCallback][注册默认认证回调（日志记录）]");
        return new NoopAuthenticationCallback();
    }

    /**
     * 注册 SIP Digest 认证默认实现
     * <p>
     * 启用条件：容器中不存在 {@link SipAuthenticationProvider} Bean 时注册。
     * 默认实现通过 AgentInfoProvider 获取坐席密码后本地计算 Digest HA1/HA2/response 比对。
     *
     * @return DefaultSipAuthenticationProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean(SipAuthenticationProvider.class)
    public SipAuthenticationProvider defaultSipAuthenticationProvider(AgentInfoProvider agentInfoProvider) {
        log.info("[defaultSipAuthenticationProvider][注册默认SIP认证（本地Digest校验）]");
        return new DefaultSipAuthenticationProvider(agentInfoProvider);
    }

    /**
     * 注册 WebSocket 握手认证默认实现
     * <p>
     * 启用条件：容器中不存在 {@link WsHandshakeAuthenticator} Bean 时注册。
     * 默认实现全部放行（返回 true），适用于内网部署无安全要求的场景。
     *
     * @return DefaultWsHandshakeAuthenticator 实例
     */
    @Bean
    @ConditionalOnMissingBean(WsHandshakeAuthenticator.class)
    public WsHandshakeAuthenticator defaultWsHandshakeAuthenticator() {
        log.info("[defaultWsHandshakeAuthenticator][注册默认WS握手认证（全部放行）]");
        return new DefaultWsHandshakeAuthenticator();
    }

    // ==================== 拦截器扩展点默认实现 ====================

    /**
     * 注册 SIP 消息拦截器默认实现
     * <p>
     * 启用条件：容器中不存在 {@link SipMessageInterceptor} Bean 时注册。
     * 默认实现为空实现（所有方法返回 false，不拦截），sipproxy 按默认逻辑转发。
     * <p>
     * 父程序实现 {@link SipMessageInterceptor} 接口并注册为 Bean 即可覆盖，
     * 典型场景：REFER 转接的 ESL 编排（originate/bridge/hold/kill）。
     *
     * @return NoopSipMessageInterceptor 实例
     */
    @Bean
    @ConditionalOnMissingBean(SipMessageInterceptor.class)
    public SipMessageInterceptor defaultSipMessageInterceptor() {
        log.info("[defaultSipMessageInterceptor][注册默认SIP消息拦截器（不拦截）]");
        return new NoopSipMessageInterceptor();
    }

    // ==================== 追踪扩展点默认实现 ====================

    /**
     * 注册链路追踪默认实现
     * <p>
     * 启用条件：容器中不存在 {@link TraceContext} Bean 时注册。
     * 默认实现使用 ThreadLocal 存储 traceId，仅在本进程内有效。
     *
     * @return DefaultTraceContext 实例
     */
    @Bean
    @ConditionalOnMissingBean(TraceContext.class)
    public TraceContext defaultTraceContext() {
        log.info("[defaultTraceContext][注册默认链路追踪（ThreadLocal）]");
        return new DefaultTraceContext();
    }

    // ==================== 传输扩展点默认实现 ====================

    /**
     * 注册 SIP 消息传输默认实现
     * <p>
     * 启用条件：容器中不存在 {@link SipMessageTransport} Bean 时注册。
     * 默认实现为空实现（不接管传输），sipproxy 使用 JAIN-SIP 内置的 UDP/TCP 传输。
     *
     * @return DefaultSipMessageTransport 实例
     */
    @Bean
    @ConditionalOnMissingBean(SipMessageTransport.class)
    public SipMessageTransport defaultSipMessageTransport() {
        log.info("[defaultSipMessageTransport][注册默认SIP消息传输（JAIN-SIP内置）]");
        return new DefaultSipMessageTransport();
    }
}
