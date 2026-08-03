# AGENTS.md

## 仓库用途

ipcc-sipproxy 是一个**完全独立的 SIP 代理服务（B2BUA）模块**（`cn.ipcc:ipcc-sipproxy:1.0.0`，包名 `cn.ipcc.sipproxy`）。提供 SIP over WebSocket、B2BUA 信令转发、会话管理、集群广播能力，通过 13 个扩展点接口与父程序解耦，可被任意 Spring Boot 工程复用。

## 技术栈

- **语言**：Java 17
- **框架**：Spring Boot 3.5.9（独立 POM，不继承 spring-boot-starter-parent，通过 `spring-boot-dependencies` BOM 管理版本）
- **SIP 协议栈**：JAIN-SIP 1.2.1.4（gov.nist 实现，统一版本避免跨版本 AbstractMethodError）
- **缓存**：Redis（StringRedisTemplate，会话与注册信息存储）
- **集群广播**：可选 Redis pub/sub / Kafka / RabbitMQ / RocketMQ（默认 local 单实例）
- **工具库**：hutool 5.8.27、lombok、log4j-over-slf4j（jain-sip-ri 桥接）

## 构建命令

```bash
mvn clean package -DskipTests         # 编译打包为 target/ipcc-sipproxy.jar
java -jar target/ipcc-sipproxy.jar    # 本地运行
```

## 项目结构

```
src/main/java/cn/ipcc/sipproxy/
├── api/              # 13 个扩展点接口（与父程序解耦）
├── autoconfigure/    # 4 个 Spring Boot 自动配置类
├── cluster/          # 集群广播（5 种 WsMessageSender 实现）
├── core/             # 入口服务、处理器、转发器、节点/会话/认证管理
│   ├── SipProxyService.java       # 入口服务（SipListener）
│   ├── annotation/SipMethod.java  # 处理器自动注册注解
│   ├── auth/GatewayAuthManager.java    # 407 鉴权管理
│   ├── forwarder/SipMessageForwarder.java  # 核心转发器
│   ├── handler/{request/{sip,ws},response}/  # @SipMethod 注解扫描
│   ├── node/SipNodeManager.java    # FS/第三方节点选择
│   ├── session/        # SessionInfo + SipSessionManager
│   └── utils/SipAnalysisUtil.java  # SIP 解析工具
├── defaults/         # 13 个扩展点默认实现（@ConditionalOnMissingBean）
├── support/          # 常量、异常、model（AgentInfo/FsNodeInfo/GatewayInfo）
└── websocket/        # WebSocket 接入（握手/重组/僵尸清理）
example/              # 示例工程（example-1-java 默认实现 / example-2-java 全扩展点覆盖 / example-jssip 前端 / test Playwright 测试）
```

## 关键文档

- **[sipproxy代码分析.md](./sipproxy代码分析.md)**：模块完整架构与代码分析（必读），涵盖 B2BUA 五层架构、请求/响应处理流程、SIP 方法处理详解、会话与状态管理、扩展点 API 详解、集群广播、自动配置、关键设计决策。
- **[README.md](./README.md)**：项目介绍、特性、快速开始、配置项与示例工程说明。

## 扩展点机制

父程序通过实现 `api/` 包下的接口并注册为 Spring Bean 即可覆盖默认行为：`AgentInfoProvider`、`FsNodeProvider`、`GatewayProvider`、`MessageSourceIdentifier`、`OutboundGatewayRewriter`、`SdpProcessor`、`IpWhitelist`、`SipRateLimiter`、`SipAuthenticationProvider`、`WsHandshakeAuthenticator`、`AuthenticationCallback`、`SipMessageInterceptor`（REFER ESL 编排委托，关键解耦点）、`TraceContext`、`SipMessageTransport`。

## 开发约束

- **不直连 FreeSWITCH ESL**：REFER 转接等需 ESL 编排的场景通过 `SipMessageInterceptor` 扩展点委托父程序实现
- **不做呼叫决策**：所有 INVITE 统一 park 到 FS，号码路由/IVR/bridge 由 ESL 层完成
- **B2BUA 头域改写**：`modifyHeadersForForwarding` 替换 Contact/Via 为 sipproxy 公网地址，移除 Record-Route
- **会话状态依赖 Redis**：SessionInfo TTL=120s，注册信息 TTL=3600s，会话内方法到达时刷新 TTL
- **JAIN-SIP 版本统一**：必须使用 1.2.1.4，避免跨版本 AbstractMethodError
- **新增处理器**：在 `core/handler/request/{sip,ws}/` 下创建类并标注 `@SipMethod("XXX")`，工厂自动扫描注册

## Skill 引用

Skill: agents-md-generator (external)
