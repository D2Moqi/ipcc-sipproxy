# ipcc-sipproxy

> 一个完全独立的 SIP 代理服务（B2BUA）模块，基于 Spring Boot 3 + JAIN-SIP 构建，提供 SIP over WebSocket 接入、B2BUA 信令转发、会话管理与集群广播能力，通过 13 个扩展点接口与父程序完全解耦，可被任意 Spring Boot 工程复用。

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![JAIN-SIP](https://img.shields.io/badge/JAIN--SIP-1.2.1.4-blue.svg)](https://jain-sip.dev.java.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

📖 语言：[简体中文](./README.md) | [English](./README.en.md)

---

## 目录

- [简介](#简介)
- [核心特性](#核心特性)
- [技术栈](#技术栈)
- [架构概览](#架构概览)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [扩展点机制](#扩展点机制)
- [示例工程](#示例工程)
- [项目结构](#项目结构)
- [开发约束](#开发约束)
- [关键文档](#关键文档)
- [参与贡献](#参与贡献)
- [许可证](#许可证)

---

## 简介

`ipcc-sipproxy` 是一个面向呼叫中心 / IPPBX 场景的 **SIP 代理服务（B2BUA，Back-to-Back User Agent）**。它在协议层面不是简单的 SIP Proxy，而是维护两段独立对话的背靠背用户代理，承担以下五项核心职责：

| 职责 | 实现位置 | 说明 |
| --- | --- | --- |
| 坐席注册管理 | `WsRegisterRequestHandler` | 处理坐席 REGISTER 请求，Digest 认证后缓存注册信息 |
| 认证鉴权 | `SipAuthenticationProvider` 扩展点 | Digest 鉴权（HA1/HA2 + nonce 比对） |
| 请求路由 | `SipRequestHandlerFactory` / `WsSipRequestHandlerFactory` | 按 SIP 方法路由到对应处理器；号码路由由 ESL 层完成 |
| 协议转换 | `SipProxyService` + `SipWebSocketHandler` | 同时监听 UDP + TCP，WebSocket 消息经分片重组后解析为 JAIN-SIP 对象 |
| 会话管理 | `SipSessionManager` + `SessionInfo` | 维护 Call-ID → SessionInfo 映射，记录 FS 节点、第三方节点、callType、网关 ID 等会话状态 |

模块采用 **完全独立 POM** 设计（不继承 `spring-boot-starter-parent`，也不依赖 yudao 框架），通过 13 个扩展点接口与父程序解耦，默认实现保证模块可独立启动。

## 核心特性

- **B2BUA 信令核心**：两段独立 INVITE 对话、不依赖 Record-Route、BYE 两段独立协调
- **双入口接入**：UDP/TCP（JAIN-SIP）+ WebSocket（RFC 7118 `sip` 子协议），适配 JsSIP / Linphone / MicroSIP 等客户端
- **WebSocket 健壮性**：分片重组（1MB 缓冲上限）、僵尸会话定时清理、握手 token 认证
- **13 个扩展点 API**：坐席/FS 节点/网关查询、SDP 处理、IP 白名单、SIP 限流、Digest 认证、WS 握手认证、认证回调、REFER ESL 编排委托、链路追踪、自定义传输
- **集群广播**：5 种 `WsMessageSender` 实现（local / redis / kafka / rabbitmq / rocketmq），按 USER / SESSION / ALL 三种目标类型分发
- **网关 Digest 鉴权**：处理 407 Proxy Authentication Required，支持 RFC 2617 标准 Digest + qop=auth + stale 重挑战
- **Spring Boot 自动配置**：4 个 `AutoConfiguration` 类，`sipproxy.enabled=false` 即可一键关闭
- **会话状态持久化**：Redis 存储，SessionInfo TTL=120s，注册信息 TTL=3600s，会话内方法到达时刷新 TTL

## 技术栈

| 类别 | 选型 | 版本 |
| --- | --- | --- |
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.5.9（独立 POM，BOM 管理版本） |
| SIP 协议栈 | JAIN-SIP（gov.nist 实现） | 1.2.1.4（统一版本避免跨版本 AbstractMethodError） |
| 缓存 | Redis（StringRedisTemplate） | — |
| 集群广播 | Redis pub/sub / Kafka / RabbitMQ / RocketMQ | optional，默认 local |
| 工具库 | hutool / lombok / log4j-over-slf4j | 5.8.27 / 1.18.34 / 2.0.16 |

## 架构概览

sipproxy 模块采用「入口层 → 工厂层 → 处理器层 → 转发层 → 节点/会话管理层」的五层架构，辅以 WebSocket 接入层、集群广播层、扩展点 API 层：

```text
┌──────────────────────────────────────────────────────────────────────────┐
│  入口层：SipProxyService（implements SipListener）                          │
│  ├── processRequest(): UDP/TCP SIP 请求入口                                │
│  ├── processResponse(): UDP/TCP SIP 响应入口                               │
│  └── handleWebSocketSipMessage(): WebSocket SIP 消息入口                    │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
        ┌──────────────────────┴──────────────────────┐
        │                                             │
┌───────▼────────────────┐                ┌──────────▼──────────────┐
│ 工厂层（按方法路由）       │                │ 工厂层（响应统一处理）      │
│ SipRequestHandlerFactory│                │ SipResponseHandlerFactory│
│ WsSipRequestHandlerFactory│               │ → UnifiedResponseHandler │
│ （@SipMethod 注解扫描注册）│                │                          │
└───────┬────────────────┘                └──────────┬──────────────┘
        │                                             │
┌───────▼───────────────────────────────────────────▼──────────────────┐
│  处理器层：AbstractSipHandler                                          │
│  ├── handler/request/sip/ (SIP 来源：FS/第三方)                          │
│  │   ├── SipInviteRequestHandler（@SipMethod INVITE）                  │
│  │   ├── SipByeRequestHandler（@SipMethod BYE）                        │
│  │   └── SipDefaultRequestHandler（PRACK/UPDATE/INFO 等会话内方法）       │
│  └── handler/request/ws/  (WS 来源：JsSIP 坐席)                          │
│      ├── WsInviteRequestHandler / WsByeRequestHandler                  │
│      ├── WsReferRequestHandler（@SipMethod REFER）                     │
│      ├── WsRegisterRequestHandler（@SipMethod REGISTER）               │
│      └── WsOptionsRequestHandler（@SipMethod OPTIONS）                 │
└──────────────────────────────┬────────────────────────────────────────┘
                               │
┌──────────────────────────────▼────────────────────────────────────────┐
│  转发层：SipMessageForwarder                                            │
│  ├── forwardToFreeSwitch（含故障转移 + 头域改写）                          │
│  ├── forwardToThirdParty / forwardToWebSocket                          │
│  ├── forwardToOutboundGateway（豁免场景出局改写）                          │
│  ├── modifyHeadersForForwarding（标准头域改写）                          │
│  ├── handle407ProxyAuth（委托 GatewayAuthManager）                      │
│  └── identifyMessageSource（委托 MessageSourceIdentifier 扩展点）        │
└──────────────────────────────┬────────────────────────────────────────┘
                               │
        ┌──────────────────────┴──────────────────────┐
        │                                             │
┌───────▼────────────────┐                ┌──────────▼──────────────┐
│ 节点管理层                │                │ 会话管理层                 │
│ SipNodeManager           │                │ SipSessionManager        │
│ ├── FS 节点选择/缓存       │                │ ├── SessionInfo 缓存/查询  │
│ ├── 第三方节点选择          │                │ ├── 注册信息缓存           │
│ └── ViaPort 节点匹配      │                │ └── 注册信息清理           │
└─────────────────────────┘                └─────────────────────────┘
```

更详细的架构图（含 WebSocket 接入层、集群广播层、扩展点 API 层）请参阅 [sipproxy代码分析.md](./sipproxy代码分析.md)。

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Redis 5.x+（会话与注册信息存储）

### 1. 编译打包

```bash
git clone <repository-url>
cd ipcc-sipproxy
mvn clean package -DskipTests
# 产物：target/ipcc-sipproxy.jar
```

### 2. 集成到 Spring Boot 工程

在父工程 `pom.xml` 中引入依赖：

```xml
<dependency>
    <groupId>cn.ipcc</groupId>
    <artifactId>ipcc-sipproxy</artifactId>
    <version>1.0.0</version>
</dependency>
```

在 `application.yml` 中添加最小配置：

```yaml
sipproxy:
  enabled: true
  instance-id: node1
  sip:
    port: 5561
    public-ip: 127.0.0.1
  websocket:
    path: /sipproxy/ws
    require-auth: true
  cluster:
    sender-type: local
  session:
    session-ttl: 120
    register-ttl: 3600
```

启动 Spring Boot 应用，sipproxy 通过 `AutoConfiguration.imports` 自动装配，无需额外注解。

### 3. 运行示例工程

仓库 `example/` 目录提供三个开箱即用的示例，详见 [示例工程](#示例工程) 章节。

## 配置项

所有配置项以 `sipproxy.*` 为前缀，由 `SipProxyProperties` 绑定：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `sipproxy.enabled` | `true` | 模块总开关，`false` 时自动配置不生效 |
| `sipproxy.instance-id` | `node1` | 集群实例标识，多节点部署时需唯一（推荐 `${HOSTNAME:node1}`） |
| `sipproxy.sip.port` | `5561` | SIP UDP/TCP 监听端口 |
| `sipproxy.sip.public-ip` | — | 公网 IP，用于 Contact/Via 头域改写 |
| `sipproxy.websocket.path` | `/sipproxy/ws` | SIP over WebSocket 接入路径 |
| `sipproxy.websocket.require-auth` | `true` | WS 握手是否调用 `WsHandshakeAuthenticator` 校验 token |
| `sipproxy.heartbeat.idle-timeout` | `90` | WS 空闲超时（秒），超时判定为僵尸会话 |
| `sipproxy.heartbeat.zombie-clean-enabled` | `true` | 是否启用僵尸会话定时清理 |
| `sipproxy.cluster.sender-type` | `local` | 集群广播类型：`local` / `redis` / `rocketmq` / `rabbitmq` / `kafka` |
| `sipproxy.session.session-ttl` | `120` | 会话 TTL（秒），会话内方法到达时刷新 |
| `sipproxy.session.register-ttl` | `3600` | 注册信息 TTL（秒），REGISTER 续期时刷新 |

## 扩展点机制

sipproxy 通过 `api/` 包下的 13 个扩展点接口与父程序解耦。父程序实现接口并注册为 Spring Bean，即可覆盖 `defaults/` 包下通过 `@ConditionalOnMissingBean` 提供的默认实现：

| 扩展点 | 用途 |
| --- | --- |
| `AgentInfoProvider` | 坐席信息查询（替代 SysAgentService） |
| `FsNodeProvider` | 在线 FS 节点列表 |
| `GatewayProvider` | 网关查询 |
| `MessageSourceIdentifier` | 消息来源识别（6 层递进） |
| `OutboundGatewayRewriter` | 出局 INVITE 头域改写 |
| `SdpProcessor` | SDP 媒体协商 |
| `IpWhitelist` / `SipRateLimiter` | IP 白名单 / SIP 限流 |
| `SipAuthenticationProvider` | SIP Digest 认证 |
| `WsHandshakeAuthenticator` | WS 握手 token 认证 |
| `AuthenticationCallback` | 认证事件回调 |
| `SipMessageInterceptor` | **REFER ESL 编排委托**（关键解耦点） |
| `TraceContext` / `SipMessageTransport` | 链路追踪 / 自定义传输 |

**扩展点扩展示例**：

```java
@Component
public class CustomSipAuthenticationProvider implements SipAuthenticationProvider {

    private final AgentInfoProvider agentInfoProvider;

    public CustomSipAuthenticationProvider(AgentInfoProvider agentInfoProvider) {
        this.agentInfoProvider = agentInfoProvider;
    }

    @Override
    public boolean authenticate(String extension, String domain, String nonce,
                                String uri, String response, String method) {
        AgentInfo agent = agentInfoProvider.getAgent(extension, domain);
        if (agent == null || StrUtil.isBlank(agent.getPassword())) {
            return false;
        }
        // 标准 RFC 2617 Digest：HA1 = MD5(user:realm:pass), HA2 = MD5(method:uri)
        String ha1 = DigestUtil.md5Hex(extension + ":" + domain + ":" + agent.getPassword());
        String ha2 = DigestUtil.md5Hex(method + ":" + uri);
        String expected = DigestUtil.md5Hex(ha1 + ":" + nonce + ":" + ha2);
        return expected.equals(response);
    }
}
```

## 示例工程

仓库 `example/` 目录提供三个互补的示例工程，覆盖「默认实现」与「全扩展点覆盖」两种集成模式：

### example-1-java —— 默认实现 + H2 内存库

- **定位**：最小集成示例，不实现任何扩展点，全部使用 sipproxy 的 `@ConditionalOnMissingBean` 默认实现
- **数据源**：H2 内存数据库，启动时执行 `classpath:schema.sql` + `data.sql`（来源于 sipproxy jar），seed 数据：坐席 `1001/123456`、FS 节点 `127.0.0.1:5060`、网关 `127.0.0.1:5080`
- **端口**：HTTP `8081` / SIP `5561`
- **运行**：

  ```bash
  cd example/example-1-java
  mvn spring-boot:run
  ```

### example-2-java —— 14 个扩展点全自定义

- **定位**：扩展点覆盖示例，演示父程序接管全部 13 个扩展点的集成方式
- **数据源**：无数据库，所有数据硬编码在 `cn.ipcc.example.ext.*` 实现类中
- **端口**：HTTP `8082` / SIP `5562`
- **运行**：

  ```bash
  cd example/example-2-java
  mvn spring-boot:run
  ```

### example-jssip —— Vue3 + JsSIP 前端测试页

- **定位**：基于 Vue 3.5 + Element Plus 2.x + JsSIP 3.10 的 SIP 软电话测试页，用于验证 SIP 注册与基础呼叫流程
- **特性**：左侧配置区 + 软电话，右侧上下分栏（操作日志 40% / WS 消息 60%），实时展示 SIP 信令交互
- **启动**：

  ```bash
  cd example/example-jssip
  npm install
  npm run dev    # http://localhost:5173
  ```

### test —— Playwright 自动化测试

- **定位**：基于 Playwright 的端到端注册测试，对 `example-1-java` 与 `example-2-java` 各跑 5 轮（共 10 轮）
- **校验点**：坐席注册成功（状态切换为在线）+ 点击呼叫按钮提示「测试示例，仅支持注册测试」
- **运行**：

  ```bash
  cd example/test
  pip install -r requirements.txt
  playwright install chromium
  python test_registration.py
  ```

## 项目结构

```text
ipcc-sipproxy/
├── src/main/java/cn/ipcc/sipproxy/
│   ├── api/              # 13 个扩展点接口（与父程序解耦）
│   ├── autoconfigure/    # 4 个 Spring Boot 自动配置类
│   ├── cluster/          # 集群广播（5 种 WsMessageSender 实现）
│   ├── core/             # 核心服务、处理器、转发器、节点/会话/认证管理
│   │   ├── SipProxyService.java       # 入口服务（SipListener）
│   │   ├── annotation/SipMethod.java  # 处理器自动注册注解
│   │   ├── auth/GatewayAuthManager.java    # 407 鉴权管理
│   │   ├── forwarder/SipMessageForwarder.java  # 核心转发器
│   │   ├── handler/        # 请求/响应处理器（@SipMethod 注解扫描）
│   │   │   ├── request/sip/   # SIP 来源（FS/第三方）请求处理器
│   │   │   ├── request/ws/    # WS 来源（JsSIP 坐席）请求处理器
│   │   │   └── response/      # 响应处理器
│   │   ├── node/SipNodeManager.java    # FS/第三方节点选择
│   │   ├── session/        # SessionInfo + SipSessionManager
│   │   └── utils/SipAnalysisUtil.java  # SIP 解析工具
│   ├── defaults/         # 13 个扩展点默认实现（@ConditionalOnMissingBean）
│   ├── support/          # 常量、异常、model（AgentInfo/FsNodeInfo/GatewayInfo）
│   └── websocket/        # WebSocket 接入（握手/重组/僵尸清理）
├── src/main/resources/
│   ├── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   ├── schema.sql        # H2 seed 建表脚本（坐席/FS 节点/网关）
│   └── data.sql          # H2 seed 数据脚本
├── example/              # 示例工程
│   ├── example-1-java/   # 默认实现 + H2
│   ├── example-2-java/   # 14 个扩展点全自定义
│   ├── example-jssip/    # Vue3 + JsSIP 前端测试页
│   └── test/             # Playwright 自动化测试
├── pom.xml               # 独立 POM（不继承任何 parent）
├── sipproxy代码分析.md    # 模块完整架构与代码分析（必读）
├── AGENTS.md             # Agent 工作上下文
├── README.md             # 当前文档
└── README.en.md          # 英文文档
```

## 开发约束

为保证模块独立性与 B2BUA 语义一致性，贡献代码时需遵循以下约束：

- **不直连 FreeSWITCH ESL**：REFER 转接等需 ESL 编排的场景通过 `SipMessageInterceptor` 扩展点委托父程序实现，sipproxy 本身不引入 ESL 客户端依赖
- **不做呼叫决策**：所有 INVITE 统一 park 到 FS，号码路由 / IVR / bridge 由 ESL 层完成，sipproxy 仅做信令转发
- **B2BUA 头域改写**：`modifyHeadersForForwarding` 替换 Contact/Via 为 sipproxy 公网地址，移除 Record-Route，依赖 B2BUA 居中转发而非 Record-Route 留在信令路径
- **会话状态依赖 Redis**：SessionInfo TTL=120s，注册信息 TTL=3600s，会话内方法到达时刷新 TTL，不依赖内存状态
- **JAIN-SIP 版本统一**：必须使用 1.2.1.4，避免跨版本 `AbstractMethodError`
- **新增处理器**：在 `core/handler/request/{sip,ws}/` 下创建类并标注 `@SipMethod("XXX")`，工厂自动扫描注册，无需手动修改路由表

## 关键文档

- **[sipproxy代码分析.md](./sipproxy代码分析.md)**：模块完整架构与代码分析（必读），涵盖 B2BUA 五层架构与核心组件清单、请求/响应处理流程（SIP 与 WebSocket 双入口）、SIP 方法处理详解（INVITE/BYE/REFER/REGISTER/OPTIONS/PRACK 等）、会话与状态管理（SessionInfo 生命周期、Redis Key 结构）、扩展点 API 详解（13 个接口 + 默认实现）、集群广播、自动配置、关键设计决策分析、关键 SIP 头域处理矩阵。
- **[AGENTS.md](./AGENTS.md)**：Agent 工作上下文，仓库用途、技术栈、构建命令、项目结构与开发约束。
- **[README.en.md](./README.en.md)**：英文版文档。

## 参与贡献

1. Fork 本仓库
2. 新建 `feat/xxx` 或 `fix/xxx` 分支
3. 提交代码（遵循 [约定式提交](https://www.conventionalcommits.org/zh-hans/) 规范）
4. 新建 Pull Request，描述变更目的与测试方式

提交前请确保：

- `mvn clean package` 编译通过
- 若修改扩展点接口，需同步更新 `defaults/` 包下的默认实现与 `sipproxy代码分析.md`
- 若修改配置项，需同步更新 `SipProxyProperties` 与本 README 的 [配置项](#配置项) 表格

## 许可证

[MIT License](./LICENSE)
