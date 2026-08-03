# sipproxy 模块代码分析

> 完全独立的 SIP 代理服务（B2BUA），通过扩展点接口与父程序解耦，可被任意 Spring Boot 工程复用。

***

## 一、模块定位与职责

### 1.1 角色定位

依据方案文档第二章「系统组件角色定位」，ipcc-sipproxy 模块在系统中承担 **SIP 代理服务（信令核心 + 控制大脑）** 的角色，协议层面是 **B2BUA（Back-to-Back User Agent，背靠背用户代理）**，而非简单 SIP Proxy。

sipproxy 的核心职责对应方案文档中"SIP 代理服务"组件的五项关键职责：

| 职责               | 代码实现位置                                                    | 说明                                                                             |
| ---------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------ |
| 坐席注册管理           | `WsRegisterRequestHandler.doHandle()`                     | 处理坐席 REGISTER 请求，Digest 认证后缓存注册信息                                              |
| 认证鉴权             | `SipAuthenticationProvider` 扩展点（默认 `DefaultSipAuthenticationProvider`） | Digest 鉴权（HA1/HA2 + nonce 比对），父程序可实现自定义认证源                                      |
| 请求路由             | `SipRequestHandlerFactory` / `WsSipRequestHandlerFactory` | 按 SIP 方法路由到对应处理器；号码路由匹配由 ESL 层实现，sipproxy 仅做转发                                  |
| 协议转换（WS↔UDP/TCP） | `SipProxyService.initializeSipStack()` + `SipWebSocketHandler` | 同时监听 UDP + TCP，WebSocket 消息经 `SipFrameReassembler` 重组 + `SipAnalysisUtil.parseSipMessage` 解析为 JAIN SIP 对象 |
| 会话管理             | `SipSessionManager` + `SessionInfo`                       | 维护 Call-ID → SessionInfo 映射，记录 FS 节点、第三方节点、callType、网关 ID 等会话状态                |

### 1.2 B2BUA 特征

依据方案文档 2.1「B2BUA 角色定位说明」，B2BUA 与 SIP Proxy 的本质区别在代码中的体现：

- **两段独立对话**：sipproxy 在 INVITE 流程中先接收坐席侧 INVITE（一段对话），再由 ESL 层 `originate` 驱动 FS 发起第二段 INVITE 回注到 sipproxy（另一段对话）。两段 INVITE 使用不同 Call-ID。
- **状态维护**：`SessionInfo.callId` / `sessionId` / `freeSwitchNode` / `thirdPartyNode` / `callType` 字段记录了对话级状态，由 `SipSessionManager` 持久化到 Redis。
- **BYE 两段独立协调**：`WsByeRequestHandler` 处理坐席→FS 段 BYE，`SipByeRequestHandler` 处理 FS→坐席段 BYE，两段独立。
- **不依赖 Record-Route 留在信令路径**：`OutboundGatewayRewriter.rewrite()` 显式移除 Record-Route 头域，依赖 B2BUA 居中转发而非 Record-Route。

### 1.3 独立模块设计

ipcc-sipproxy 采用**完全独立 POM**设计（不继承 spring-boot-starter-parent，也不继承 父 pom），通过 `dependencyManagement` 引入 `spring-boot-dependencies` BOM 做版本管理，所有依赖版本显式指定。模块通过 **13 个扩展点接口**（`api/` 包）与父程序解耦，默认实现（`defaults/` 包）保证模块可独立启动。

**核心依赖**：
- Spring Boot 3.5.9（core / websocket / data-redis 为 compile）
- JAIN-SIP 1.2.1.4（v1.2 统一版本，避免跨版本 AbstractMethodError）
- spring-kafka / spring-rabbit / rocketmq-spring-boot-starter 2.3.1（optional，集群广播可选）
- hutool 5.8.27、lombok、log4j-over-slf4j（jain-sip-ri 内部 log4j 1.x 桥接到 slf4j）
- Java 17

***

## 二、整体架构

### 2.1 分层架构图

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
│  │   ├── AbstractSipRequestHandler                                    │
│  │   ├── SipInviteRequestHandler（@SipMethod INVITE）                  │
│  │   ├── SipByeRequestHandler（@SipMethod BYE）                        │
│  │   └── SipDefaultRequestHandler（PRACK/UPDATE/INFO 等会话内方法）       │
│  └── handler/request/ws/  (WS 来源：JsSIP 坐席)                          │
│      ├── AbstractWsSipRequestHandler                                  │
│      ├── WsInviteRequestHandler（@SipMethod INVITE）                   │
│      ├── WsByeRequestHandler（@SipMethod BYE）                         │
│      ├── WsReferRequestHandler（@SipMethod REFER）                     │
│      ├── WsRegisterRequestHandler（@SipMethod REGISTER）               │
│      ├── WsOptionsRequestHandler（@SipMethod OPTIONS）                 │
│      └── WsDefaultRequestHandler                                      │
└──────────────────────────────┬────────────────────────────────────────┘
                               │
┌──────────────────────────────▼────────────────────────────────────────┐
│  转发层：SipMessageForwarder                                            │
│  ├── forwardToFreeSwitch（含故障转移 + 头域改写）                          │
│  ├── forwardToThirdParty                                              │
│  ├── forwardToWebSocket / forwardToWebSocketByUser                    │
│  ├── forwardToOutboundGateway（豁免场景出局改写）                          │
│  ├── modifyHeadersForForwarding（标准头域改写）                          │
│  ├── modifyWsProxyHeaders（WebSocket 头域改写）                         │
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
│ ├── 备用节点选择           │                │ └── 注册信息清理           │
│ └── ViaPort 节点匹配      │                │                          │
└─────────────────────────┘                └─────────────────────────┘
        │                                             │
        └──────────────────────┬──────────────────────┘
                               │
                    ┌──────────▼──────────────┐
                    │ 支撑层                    │
                    │ ├── annotation/SipMethod  │
                    │ ├── auth/GatewayAuthManager│
                    │ ├── support/RedisConstants│
                    │ ├── support/SipProxyConstants│
                    │ ├── support/SipProxyException│
                    │ ├── utils/SipAnalysisUtil  │
                    │ └── session/SessionInfo   │
                    └─────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  WebSocket 接入层（独立模块）                                              │
│  ├── SipWebSocketHandler（TextWebSocketHandler）                       │
│  ├── SipHandshakeInterceptor（token 校验 + RFC 7118 子协议协商）         │
│  ├── SipFrameReassembler（SIP 消息分片重组，1MB 缓冲上限）                │
│  ├── LocalWsSessionManager / WsSessionManager                         │
│  └── ZombieSessionCleaner（@Scheduled 清理僵尸会话）                     │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  集群广播层（多实例部署）                                                  │
│  ├── WsMessageSender 接口（5 种实现：local/redis/kafka/rabbitmq/rocketmq）│
│  ├── SipWsBroadcastMessage（消息载体：USER/SESSION/ALL 三种目标类型）      │
│  └── ClusterBroadcastConsumer（按 targetType 分发到本实例 WS 会话）        │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  扩展点 API 层（api/ 包，13 个接口 + defaults/ 包默认实现）                │
│  ├── AgentInfoProvider / FsNodeProvider / GatewayProvider             │
│  ├── MessageSourceIdentifier / OutboundGatewayRewriter               │
│  ├── SdpProcessor / IpWhitelist / SipRateLimiter                     │
│  ├── SipAuthenticationProvider / WsHandshakeAuthenticator            │
│  ├── AuthenticationCallback / SipMessageInterceptor                  │
│  ├── TraceContext / SipMessageTransport                              │
│  └── defaults/ 下 13 个默认实现（@ConditionalOnMissingBean 条件注册）     │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件清单

| 类名                            | 路径（相对 cn/ipcc/sipproxy/）                              | 职责                                                                                            | 关键方法                                                                                                         |
| ----------------------------- | ----------------------------------------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `SipProxyService`             | `core/SipProxyService.java`                           | 入口服务，初始化 SIP 栈，分发请求/响应到对应工厂                                                                   | `init()`、`processRequest()`、`processResponse()`、`handleWebSocketSipMessage()`                                |
| `SipMethod`                   | `core/annotation/SipMethod.java`                      | 标注处理器对应的 SIP 方法，工厂自动扫描注册                                                                      | `value()`                                                                                                    |
| `RedisConstants`              | `support/RedisConstants.java`                         | Redis Key 前缀与 TTL 常量                                                                          | `SESSION_INFO_PREFIX`、`SESSION_FS_NODE_PREFIX`、`SESSION_THIRD_PARTY_PREFIX`                     |
| `SipProxyConstants`           | `support/SipProxyConstants.java`                      | 信令来源、callType、JSSIP 标识、支持方法集合等常量                                                              | `WEBSOCKET`、`FREESWITCH`、`THIRD_PARTY`、`CALL_TYPE_INTERNAL/INBOUND/OUTBOUND`                                 |
| `SipProxyException`           | `support/SipProxyException.java`                      | 模块统一运行时异常，含错误码字段                                                                              | `getCode()`                                                                                                  |
| `SipProxyErrorCodeConstants`  | `support/SipProxyErrorCodeConstants.java`             | 错误码常量（500-599 区间）                                                                            | `INTERNAL_SERVER_ERROR`、`NO_AVAILABLE_FS_NODE`、`FORWARD_FAILED`                                              |
| `SipMessageForwarder`         | `core/forwarder/SipMessageForwarder.java`             | 核心转发器，封装 FS/第三方/WS/出局网关四种转发目标，含头域改写与故障转移                                                       | `forwardToFreeSwitch()`、`forwardToOutboundGateway()`、`handle407ProxyAuth()`、`modifyHeadersForForwarding()`        |
| `SipNodeManager`              | `core/node/SipNodeManager.java`                       | FS 节点选择（一致性哈希）、第三方节点按来源 IP 反查、备用节点选择、ViaPort 节点匹配                                              | `selectFreeSwitchNode()`、`selectFreeSwitchNodeByViaPort()`、`selectThirdPartyNode()`、`selectAlternativeFreeSwitchNode()` |
| `SessionInfo`                 | `core/session/SessionInfo.java`                       | 会话信息数据载体（Call-ID、sessionId、FS 节点、第三方节点、callType、网关 ID、407 鉴权上下文等）                              | `getCallId()`、`getFreeSwitchNode()`、`getThirdPartyNode()`、`getCallType()`、`getGatewayId()`、`getAuthChallengeCount()` |
| `SipSessionManager`           | `core/session/SipSessionManager.java`                 | 会话信息与注册信息的 Redis 读写                                                                           | `cacheSessionInfo()`、`getSessionInfo()`、`cacheRegisterInfo()`、`getSessionIdByUser()`                         |
| `GatewayAuthManager`          | `core/auth/GatewayAuthManager.java`                   | 网关 Digest 鉴权管理，处理 407 Proxy Authentication Required，支持 RFC 2617 标准 Digest + qop=auth + stale 重挑战         | `handle407Challenge()`、`canRetry()`、`calculateDigest()`、`calculateDigestQop()`                              |
| `SipAnalysisUtil`             | `core/utils/SipAnalysisUtil.java`                     | SIP 文本/对象解析、头域提取、来源 IP 提取、响应构造等工具方法                                                           | `parseSipMessage()`、`getCallId()`、`getSourceIpFromMessage()`、`extractToUser()`、`buildResponse()`             |
| `AbstractSipHandler`          | `core/handler/AbstractSipHandler.java`                | 所有 SIP 处理器公共基类，提供注册用户校验、坐席在线检测能力                                                              | `isRegisteredUser()`、`isAgentOnline()`                                                                       |
| `AbstractSipRequestHandler`   | `core/handler/request/sip/AbstractSipRequestHandler.java`  | SIP 来源（FS/第三方）请求处理器基类，定义 handle 抽象方法与按注册转发兜底                                                  | `handle()`、`forwardRequestByRegistration()`                                                                  |
| `SipRequestHandlerFactory`    | `core/handler/request/sip/SipRequestHandlerFactory.java`   | SIP 请求处理器工厂，@SipMethod 注解自动注册                                                                 | `getHandler()`、`getDefaultHandler()`、`init()`                                                                |
| `SipInviteRequestHandler`     | `core/handler/request/sip/SipInviteRequestHandler.java`    | SIP 来源 INVITE 处理器，识别豁免场景直接出局、识别已注册坐席直接推 WebSocket                                              | `handle()`、`extractGatewayId()`                                                                              |
| `SipByeRequestHandler`        | `core/handler/request/sip/SipByeRequestHandler.java`       | SIP 来源 BYE 处理器，按 To 头注册状态转发到坐席/第三方                                                            | `handle()`                                                                                                   |
| `SipDefaultRequestHandler`    | `core/handler/request/sip/SipDefaultRequestHandler.java`   | SIP 来源默认处理器（PRACK/UPDATE/INFO 等会话内方法），按 Call-ID 查 SessionInfo + ResponseForwardingStrategy 决策 | `handle()`、`forwardByTarget()`                                                                               |
| `AbstractWsSipRequestHandler` | `core/handler/request/ws/AbstractWsSipRequestHandler.java` | WS 来源请求处理器基类，模板方法 handle 校验 Call-ID 后调 doHandle                                               | `handle()`、`validateRequest()`、`sendTryingResponse()`、`sendErrorResponse()`                                  |
| `WsSipRequestHandlerFactory`  | `core/handler/request/ws/WsSipRequestHandlerFactory.java`  | WS 请求处理器工厂，@SipMethod 注解自动注册                                                                  | `getHandler()`、`getDefaultHandler()`、`init()`                                                                |
| `WsInviteRequestHandler`      | `core/handler/request/ws/WsInviteRequestHandler.java`      | 坐席 INVITE 处理器，re-INVITE 检测、统一转发到 FS park，提取 X-Gateway-Id                                      | `doHandle()`、`extractGatewayId()`                                                                            |
| `WsByeRequestHandler`         | `core/handler/request/ws/WsByeRequestHandler.java`         | 坐席 BYE 处理器，转发到会话绑定的 FS 节点                                                                     | `doHandle()`                                                                                                 |
| `WsReferRequestHandler`       | `core/handler/request/ws/WsReferRequestHandler.java`       | 坐席 REFER 转接处理器，通过 SipMessageInterceptor 扩展点委托父程序实现 ESL 编排                                    | `doHandle()`、`forwardToFreeSwitchByDefault()`                                                                |
| `WsRegisterRequestHandler`    | `core/handler/request/ws/WsRegisterRequestHandler.java`    | 坐席 REGISTER 处理器，Digest 鉴权 + 注册信息缓存（委托 SipAuthenticationProvider 扩展点）                          | `doHandle()`、`send401Response()`、`send200OkResponse()`                                                       |
| `WsOptionsRequestHandler`     | `core/handler/request/ws/WsOptionsRequestHandler.java`     | 坐席 OPTIONS 心跳处理器，回 200 OK + Allow 头                                                           | `doHandle()`                                                                                                 |
| `WsDefaultRequestHandler`     | `core/handler/request/ws/WsDefaultRequestHandler.java`     | 坐席默认处理器，会话内方法（PRACK/UPDATE/INFO 等）按 SessionInfo + ResponseForwardingStrategy 决策               | `doHandle()`、`forwardToFreeSwitchBySession()`                                                                |
| `AbstractSipResponseHandler`  | `core/handler/response/AbstractSipResponseHandler.java`    | 响应处理器基类，定义 determineResponseTarget + forwardResponse 模板方法，含 407 拦截逻辑                          | `handle()`、`determineResponseTarget()`、`forwardResponse()`                                                   |
| `ResponseForwardingStrategy`  | `core/handler/response/ResponseForwardingStrategy.java`    | 响应转发策略表 source × callType → target 三维映射                                                       | `getForwardingTarget()`                                                                                      |
| `SipResponseHandlerFactory`   | `core/handler/response/SipResponseHandlerFactory.java`     | 响应处理器工厂，统一返回 UnifiedResponseHandler                                                           | `getHandler()`                                                                                               |
| `UnifiedResponseHandler`      | `core/handler/response/UnifiedResponseHandler.java`        | 统一响应处理器，按策略表决策并按 target 分支转发，含 SessionInfo 上下文来源校正                                              | `determineResponseTarget()`、`correctSourceBySessionContext()`、`forwardResponse()`                             |

### 2.3 核心数据结构

#### 2.3.1 SessionInfo（会话信息）

`SessionInfo` 是 sipproxy 维护会话状态的核心数据载体，通过 Jackson 序列化存入 Redis，字段定义如下：

| 字段                                       | 类型                | 说明                                                                |
| ---------------------------------------- | ----------------- | ----------------------------------------------------------------- |
| `callId`                                 | String            | SIP Call-ID，会话唯一标识                                                |
| `sessionId`                              | String            | WebSocket 会话 ID（坐席端 JsSIP 标识）                                     |
| `freeSwitchNode`                         | FsNodeInfo        | 当前会话绑定的 FreeSWITCH 节点（用于 BYE/会话内方法转发一致）                           |
| `thirdPartyNode`                         | GatewayInfo        | 入局场景的来源第三方网关节点（用于响应回送）                                            |
| `callType`                               | String            | 呼叫类型：`INTERNAL`/`OUTBOUND`/`INBOUND`，决定响应转发方向                     |
| `toSipTransport`                         | String            | 转发到 FS/第三方时使用的传输协议（udp/tcp，默认 udp）                                |
| `websocketContactName/Ip/Port/Transport` | String/int/String | WebSocket 端 Contact 头四元组，用于 `modifyWsProxyHeaders` 改写 Request-URI |
| `gatewayId`                              | String            | 从 INVITE 头 `X-Gateway-Id` 提取，作为 IVR 转接节点的网关覆盖项                    |
| `authChallengeCount`                     | int               | 407 鉴权挑战计数（初始 0，最大 2），支持 stale=true 重挑战                          |
| `last407Nonce`                           | String            | 上次 407 响应的 nonce，用于检测 nonce 更新                                   |
| `originalInviteText`                     | String            | 出局 INVITE 原始文本缓存，407 鉴权重发时还原 INVITE 用                            |
| `authRetried`                            | boolean           | `@Deprecated`，v1.2 由 `authChallengeCount` 替代，过渡期保留用于 JSON 反序列化兼容 |

#### 2.3.2 节点信息模型

模块使用独立 model（`support/model/` 包）：

**FsNodeInfo**（FreeSWITCH 节点）：
- `id`：节点 ID
- `name`：节点名称
- `sipIp` / `sipPort`：SIP 信令地址（UDP/TCP）
- `eslIp` / `eslPort`：ESL 连接地址
- `status`：在线状态（0=在线）

**GatewayInfo**（第三方网关节点）：
- `id` / `name`：网关标识
- `address` / `port`：SIP 信令地址
- `externalLineNumber`：DID 外显号码
- `fromDomain`：From 头域名
- `callerIdInFrom`：主叫号码策略（0=原始主叫，1=外显号码）
- `authType`：认证类型（1=需 Digest 鉴权）
- `transportProtocol`：传输协议
- `authAddress` / `authPort`：鉴权地址
- `username` / `password`：Digest 鉴权凭证
- `retrySeconds` / `pingSeconds` / `expireSeconds`：网关心跳与重试参数
- `status`：状态（0=启用，1=禁用）

**AgentInfo**（坐席信息）：
- `extension`：分机号
- `domain`：域名
- `password`：密码（Digest 鉴权用）
- `agentId` / `tenantId` / `displayName`：业务标识

#### 2.3.3 Redis 常量

`RedisConstants` 定义的 sipproxy 相关 Redis Key（命名规范 `ipcc:sipproxy:<category>:<sub-key>`）：

| Redis Key 前缀                           | 数据结构         | 说明                               | TTL（秒） |
| -------------------------------------- | ------------ | -------------------------------- | ------ |
| `ipcc:sipproxy:session:info:{callId}`            | String（JSON） | SessionInfo 完整会话信息               | 120    |
| `ipcc:sipproxy:session:register:{sessionId}`     | String       | 注册映射，值格式 `username:domain`       | 3600   |
| `ipcc:sipproxy:user:session:{username}:{domain}` | String       | 用户→WebSocket sessionId 反查映射      | 3600   |
| `ipcc:sipproxy:session:fsnode:{callId}`          | String（JSON） | 会话绑定的 FS 节点（用于 BYE/REFER 跨方法一致性） | 120    |
| `ipcc:sipproxy:session:thirdparty:{callId}`      | String（JSON） | 会话绑定的第三方网关节点（用于响应回送）             | 120    |
| `ipcc:sipproxy:message:record:{...}`             | -            | SIP 消息记录 Key 前缀（已定义但本模块未使用）      | -      |

**TTL 设计依据**：
- `REFRESH_TIME = 120`（秒）：会话级缓存（SessionInfo/节点绑定），会话活跃期间通过 `updateSessionInfo` 刷新
- `REGISTER_REFRESH_TIME = 3600`（秒）：注册信息缓存，大于 JsSIP 默认 REGISTER Expires（1800s），避免"WS 连接存活但 Redis 缓存过期"
- `EXPIRATION = 720`（分钟）：消息记录兜底 TTL

***

## 三、请求处理流程

### 3.1 SIP 请求处理流程（第三方/FS → sipproxy）

SIP 请求通过 UDP/TCP ListeningPoint 进入 `SipProxyService.processRequest(RequestEvent)`，完整链路如下：

```text
[1] UDP/TCP 收到 SIP 请求
    │
    ▼
[2] SipProxyService.processRequest()
    ├── cleanViaHeaderForTcpRequest()：清理 TCP 请求 Via 头的 received/rport 参数
    ├── SipAnalysisUtil.getCallId()：提取 Call-ID
    ├── messageSourceIdentifier.identifySource()：识别来源（FREESWITCH/THIRD_PARTY/WEBSOCKET）
    ├── sipRateLimiter.tryAcquire()：限流校验（失败返回 429 Too Many Requests）
    └── THIRD_PARTY 来源：ipWhitelist.isAllowed()（失败返回 403 Forbidden）
    │
    ▼
[3] SipRequestHandlerFactory.getHandler(method)：按方法路由处理器
    ├── INVITE → SipInviteRequestHandler
    ├── BYE    → SipByeRequestHandler
    └── 其他   → SipDefaultRequestHandler（默认）
    │
    ▼
[4] AbstractSipRequestHandler.handle(request, callId, source)
    │
    ▼
[5] 具体 Handler 处理 → SipMessageForwarder.forwardToXxx() 转发
```

**消息来源识别**（`MessageSourceIdentifier` 扩展点，默认 `DefaultMessageSourceIdentifier`）采用 6 层递进识别：

1. **X-FS-Source 头**（最高优先级）：自有 FS originate 注入的自定义头，区分同 IP 下 FS 与第三方网关
2. **坐席记录匹配**（From 头 extension+domain）：委托 `AgentInfoProvider.getAgent`，存在 → `WEBSOCKET`。**主判断逻辑**，兼容 JsSIP/WebRTC/软电话/硬电话等任意客户端类型（domain 剥离端口）
3. **JsSIP UA 兜底**：User-Agent 含 `IPCC_JSSIP` → `WEBSOCKET`
4. **FS 节点 IP+端口精确匹配**：遍历 `fsNodeProvider.listFsNodes`，IP 相等 + 端口相等（或节点 port=null）→ `FREESWITCH`
5. **第三方网关 IP 匹配**（忽略端口）：遍历 `gatewayProvider.listEnabledGateways`，`sourceIp.equals(gateway.address)` → `THIRD_PARTY`
6. **FS UA 兜底**：User-Agent 含 `FREESWITCH` → `FREESWITCH`
7. **兜底**：`WEBSOCKET`

**INVITE 处理链路**（`SipInviteRequestHandler.handle()`）：

```text
提取 To/From 头 → 校验 To 头完整性
    │
    ▼
提取 X-Gateway-Id 头
    │
    ▼
查/建 SessionInfo：
    ├── 已存在：warn 日志
    └── 不存在：根据 source + gatewayId 决定 callType
        ├── FREESWITCH + 携带 gatewayId → OUTBOUND（c-leg 出局腿）
        ├── FREESWITCH + 未携带 gatewayId → INTERNAL（FS 内部回环）
        ├── THIRD_PARTY → INBOUND（按 sourceIp 反查 thirdPartyNode 缓存）
        └── 其他 → INTERNAL
    设置 toSipTransport（按 Via 头）
    选择 FS 节点 → selectFreeSwitchNode(callId)
    保存 gatewayId 到 SessionInfo
    cacheSessionInfo()
    │
    ▼
豁免分支：FREESWITCH + 携带 gatewayId
    └── messageForwarder.forwardToOutboundGateway(request, gatewayId) → 直接出局改写
    │
    ▼
快速推 WebSocket 分支：FREESWITCH + 未携带 gatewayId + 被叫是已注册 JsSIP 坐席
    ├── selectFreeSwitchNodeByViaPort(callId, viaPort)：按 Via 端口覆盖 freeSwitchNode
    └── messageForwarder.forwardToWebSocketByUser(toUser, agentDomain, request)
    （避免 FS originate→sipproxy→FS park 死循环，绕过 FS WebRTC SDP 协商失败）
    │
    ▼
默认分支：forwardToFreeSwitch(request, freeSwitchNode) → FS park
```

**BYE 处理链路**（`SipByeRequestHandler.handle()`）：

```text
提取 To 头 → 校验 → forwardRequestByRegistration(request, callId, toUser, toDomain)
    ├── 已注册坐席 → forwardToWebSocketByUser(toUser, toDomain, request)
    └── 未注册    → selectThirdPartyNode(callId, sourceIp) → forwardToThirdParty
```

**PRACK/UPDATE/INFO 等会话内方法处理链路**（`SipDefaultRequestHandler.handle()`）：

```text
Call-ID 为空 → fallbackForwardByRegistration（按 To 头查注册转发）
    │
    ▼
提取 To 头校验 → 失败返回 BAD_REQUEST
    │
    ▼
查 SessionInfo：
    ├── 不存在 → forwardRequestByRegistration（fallback 按注册转发）
    └── 存在   → updateSessionInfo 刷新会话
        │
        ▼
ResponseForwardingStrategy.getForwardingTarget(source, callType) → target
    │
    ▼
forwardByTarget：
    ├── WEBSOCKET   → modifyWsProxyHeaders + forwardToWebSocket(sessionId)
    ├── FREESWITCH  → forwardToFreeSwitch(freeSwitchNode)
    ├── THIRD_PARTY → forwardToThirdParty(thirdPartyNode)
    └── 未知        → fallback forwardRequestByRegistration
```

### 3.2 WebSocket 请求处理流程（坐席 → sipproxy）

WebSocket SIP 消息（JsSIP 客户端发送的 SIP 文本）处理链路：

```text
[1] WebSocket 收到 SIP 文本帧
    │
    ▼
[2] SipWebSocketHandler.handleTextMessage()
    ├── 更新 lastActiveAt（用于僵尸会话清理）
    └── sipFrameReassembler.reassemble()：分片重组（1MB 缓冲上限）
    │
    ▼
[3] SipProxyService.handleWebSocketSipMessage(sessionId, sipMessageStr)
    ├── SipAnalysisUtil.parseSipMessage()：解析为 JAIN SIP Message
    ├── SipAnalysisUtil.getCallId()：提取 Call-ID
    ├── 查 SessionInfo → 若存在则更新 WebSocket Contact 信息
    │   └── extractContact() → 设置 websocketContactName/Ip/Port/Transport
    ├── 添加 User-Agent: IPCC_JSSIP 头
    │
    ▼
[4] 按 Message 类型分发
    ├── Request → WsSipRequestHandlerFactory.getHandler(method)
    │   ├── INVITE    → WsInviteRequestHandler
    │   ├── BYE       → WsByeRequestHandler
    │   ├── REFER     → WsReferRequestHandler
    │   ├── REGISTER  → WsRegisterRequestHandler
    │   ├── OPTIONS   → WsOptionsRequestHandler
    │   └── 其他      → WsDefaultRequestHandler
    └── Response → SipResponseHandlerFactory.getHandler(response)
        │
        ▼
[5] AbstractWsSipRequestHandler.handle(sessionId, request)（模板方法）
    ├── 校验 Call-ID 非空
    ├── validateRequest()：校验 To 头完整性
    └── doHandle()（具体处理器实现）
```

**INVITE 处理链路**（`WsInviteRequestHandler.doHandle()`）：

```text
设置 traceId
    │
    ▼
re-INVITE 检测：Call-ID 已存在 SessionInfo 且有 freeSwitchNode
    └── 是 → 直接 forwardToFreeSwitch 到已有 FS（hold/unhold/Session Timer 刷新）
    │
    ▼
提取 From/To → sendTryingResponse(100 Trying)
    │
    ▼
selectFreeSwitchNode(callId) → 选择 FS 节点
    │
    ▼
构造 SessionInfo：
    ├── sessionId = WebSocket 会话 ID
    ├── freeSwitchNode = 选定 FS
    ├── callType = OUTBOUND（统一标记，由 ESL 层走呼出号码路由匹配）
    ├── websocketContactXxx = extractContact() 提取
    └── gatewayId = extractGatewayId()（如 INVITE 头携带 X-Gateway-Id）
    │
    ▼
cacheSessionInfo() → forwardToFreeSwitch(request, freeSwitchNode) → FS park
```

**REFER 处理链路**（`WsReferRequestHandler.doHandle()`）：

```text
设置 traceId
    │
    ▼
提取 Refer-To、X-Gateway-Id、X-Transfer-Type（默认 blind）
    │
    ▼
校验 Refer-To 非空
    │
    ▼
检测 SipMessageInterceptor 扩展点（@Autowired(required=false)）：
    ├── 存在且 preWsToSip(request)=true
    │   └── 父程序已接管 ESL 编排（originate/bridge/hold/kill），回复 202 Accepted 后返回
    └── 不存在或返回 false
        └── forwardToFreeSwitchByDefault(request, callId)（透明转发到 FS）
    │
    ▼
回复 202 Accepted（RFC 3515 要求）
```

**REGISTER 处理链路**（`WsRegisterRequestHandler.doHandle()`）：

```text
getAuthorization() → 无 Authorization 头 → send401Response（带 WWW-Authenticate: Digest nonce）
    │
    ▼
validateCredentials() 委托 SipAuthenticationProvider.authenticate 扩展点
    └── 默认 DefaultSipAuthenticationProvider 本地 Digest 校验：
        HA1 = MD5(username:realm:password)
        HA2 = MD5(method:uri)
        expectedResponse = MD5(HA1:nonce:HA2)
        比对 expectedResponse.equals(response)
    │
    ▼
校验通过 → send200OkResponse + cacheRegisterInfo(sessionId, username, realm) + AuthenticationCallback.onSuccess
校验失败 → send403Response + AuthenticationCallback.onFailure
```

### 3.3 响应处理流程

响应通过两个入口进入：

1. **UDP/TCP 响应**：`SipProxyService.processResponse(ResponseEvent)` → `SipResponseHandlerFactory.getHandler(response)`
2. **WebSocket 响应**：`SipProxyService.handleWebSocketSipMessage()` 中识别为 Response → `SipResponseHandlerFactory.getHandler(response)`

两者最终都进入 `UnifiedResponseHandler.handle()`：

```text
UnifiedResponseHandler.handle(response)
    │
    ▼
AbstractSipResponseHandler.handle()：
    ├── getCallId(response)
    ├── sessionManager.getSessionInfo(callId) → 不存在 warn 返回
    ├── 407 拦截：source=THIRD_PARTY + gatewayId 非空
    │   └── messageForwarder.handle407ProxyAuth(response, sessionInfo)
    │       └── 成功 → 拦截 407 不转发（GatewayAuthManager 重发 INVITE）
    │
    ▼
determineResponseTarget(response, sessionInfo)：
    ├── messageSourceIdentifier.identifySource(response) → source
    ├── correctSourceBySessionContext(source, sessionInfo)（SessionInfo 上下文校正）
    │   ├── 存在 thirdPartyNode 或 gatewayId → 强制 source=THIRD_PARTY
    │   └── 仅存在 freeSwitchNode + 初始 source=WEBSOCKET → 校正为 FREESWITCH
    ├── sessionInfo.getCallType() → callType
    └── forwardingStrategy.getForwardingTarget(source, callType) → target
    │
    ▼
forwardResponse(response, target, sessionInfo)：
    ├── WEBSOCKET   → modifyWsProxyHeaders(response) + forwardToWebSocket(sessionId)
    ├── FREESWITCH  → forwardToFreeSwitch(freeSwitchNode)
    ├── THIRD_PARTY → forwardToThirdParty(thirdPartyNode)
    └── 未知        → warn
```

**响应转发策略表**（`ResponseForwardingStrategy.initializeStrategy()`）：

| source \ callType | INTERNAL   | OUTBOUND   | INBOUND      |
| ----------------- | ---------- | ---------- | ------------ |
| WEBSOCKET         | FREESWITCH | FREESWITCH | FREESWITCH   |
| FREESWITCH        | WEBSOCKET  | WEBSOCKET  | THIRD\_PARTY |
| THIRD\_PARTY      | FREESWITCH | FREESWITCH | FREESWITCH   |

策略语义解释（与方案文档第 6.2.3、7.2 节呼应）：

- **WEBSOCKET 来源响应**：坐席 JsSIP 发的响应（如 200 OK）一律转发到 FS，由 FS 处理媒体锚定
- **FREESWITCH 来源响应**：
  - INTERNAL/OUTBOUND（c-leg 出局腿响应）→ 转回坐席 WebSocket
  - INBOUND（入局呼叫 FS 给的响应）→ 转回第三方网关
- **THIRD\_PARTY 来源响应**：第三方网关的响应一律转发到 FS，由 FS 通过 ESL bridgeCall 桥接两腿

***

## 四、核心组件详解

### 4.1 SipProxyService（入口服务）

**类职责**：实现 `javax.sip.SipListener`，是 sipproxy 模块的入口服务，负责：

1. 初始化 SIP 栈（UDP + TCP 双协议监听）
2. 处理 UDP/TCP SIP 请求与响应
3. 处理 WebSocket SIP 消息（来自 JsSIP 坐席）
4. 生命周期管理（@PostConstruct 启动、@PreDestroy 销毁）

**字段依赖（@Resource 注入）**：
- `SipSessionManager`、`SipMessageForwarder`、`WsSipRequestHandlerFactory`、`SipRequestHandlerFactory`、`SipResponseHandlerFactory`、`SipProxyProperties`、`GatewayAuthManager`、`MessageSourceIdentifier`、`IpWhitelist`、`SipRateLimiter`

**关键方法**：

- `init()`：调用 `initializeSipStack()` 创建 SipStack、MessageFactory、HeaderFactory、AddressFactory，绑定 `0.0.0.0:{sipPort}` 的 UDP/TCP ListeningPoint，并调用 `initializeHandlers()` 设置工厂与转发器的工厂实例。
- `initializeSipStack()`：使用 `gov.nist` JAIN SIP 实现，配置 `javax.sip.AUTOMATIC_DIALOG_SUPPORT=off`（关闭自动对话支持，B2BUA 自管理对话状态）。
- `processRequest(RequestEvent)`：清理 TCP Via 头 → 委托 `MessageSourceIdentifier.identifySource` 识别来源 → 委托 `SipRateLimiter.tryAcquire` 限流（失败返回 429）→ THIRD_PARTY 来源委托 `IpWhitelist.isAllowed` 校验（失败返回 403）→ 工厂路由到处理器。
- `processResponse(ResponseEvent)`：提取状态码与 Call-ID → 工厂获取响应处理器 → 调用 handle。
- `handleWebSocketSipMessage(sessionId, sipMessageStr)`：解析文本 → 提取 Call-ID → 更新 WebSocket Contact 信息 → 添加 `User-Agent: IPCC_JSSIP` 头 → 按请求/响应分发到对应工厂。
- `cleanViaHeaderForTcpRequest(request)`：移除 JAIN SIP 栈自动添加的 `received`/`rport` 参数（RFC 3581 NAT 穿透），仅保留 transport/host/port/branch。
- `cleanupRegisterInfo(sessionId)`：WebSocket 连接关闭时调用，清理注册映射。
- `processTimeout()` / `processIOException()` / `processTransactionTerminated()` / `processDialogTerminated()`：JAIN SipListener 回调，仅记录日志，不参与业务流程。

**初始化流程关键点**：

```text
@PostConstruct init()
    │
    ├── initializeSipStack()：创建 SipStack + ListeningPoint(UDP/TCP) + SipProvider
    │
    └── initializeHandlers()：
        ├── handlerFactory.setHeaderFactory/setMessageFactory + handlerFactory.init()
        ├── sipRequestHandlerFactory.setHeaderFactory + sipRequestHandlerFactory.init()
        ├── messageForwarder.setSipProvider/setSipProviderTcp/setHeaderFactory/...
        └── gatewayAuthManager.init(headerFactory, addressFactory, sipProvider, sipProviderTcp)
```

`handlerFactory.init()` 通过 Spring 注入的 `Map<String, AbstractWsSipRequestHandler> handlerBeans`，扫描每个 Bean 上的 `@SipMethod` 注解，将方法名→处理器映射注册到 `handlerMap`。新增处理器只需加 `@SipMethod("XXX")` 注解即可被自动注册，无需修改工厂代码。

### 4.2 SipMessageForwarder（消息转发器）

**类职责**：`@Component`，sipproxy 的核心组件，封装四种转发目标（FS/第三方/WS/出局网关）的发送逻辑、头域改写与故障转移。

**字段依赖**：`SipSessionManager`、`WsSessionManager`、`SipNodeManager`、`GatewayProvider`、`GatewayAuthManager`、`OutboundGatewayRewriter`、`SdpProcessor`、`SipProxyProperties`。JAIN-SIP 工厂与 SipProvider 由 SipProxyService 启动时通过 Setter 注入。

**关键方法详解**：

#### 4.2.1 `forwardToFreeSwitch(message, node)` —— 转发到 FS（含故障转移）

```text
forwardToFreeSwitch(message, node)
    │
    ▼
while(true):
    ├── triedNodes.add(currentNode)
    ├── modifyHeadersForForwarding(message, currentNode, triedNodes.size())  // 改写头域
    ├── sdpProcessor.process(modifiedMessage)                                 // SDP 处理（默认透传）
    ├── doForwardToFreeSwitch(modifiedMessage, currentNode)                   // 实际发送
    │   ├── 按 sessionInfo.toSipTransport 选 UDP/TCP SipProvider
    │   ├── Request → targetProvider.sendRequest(request)
    │   └── Response → targetProvider.sendResponse(response)
    │   ├── 成功 → return
    │   └── 失败 → 抛出异常
    │
    └── catch 异常:
        ├── nodeManager.selectAlternativeFreeSwitchNode(triedNodes, callId)
        │   ├── 找到 → currentNode = alternativeNode, continue
        │   └── 未找到 → 抛 SipProxyException("已尝试 N 个节点均失败")
```

#### 4.2.2 `forwardToThirdParty(message, node)` —— 转发到第三方 SIP

逻辑与 `forwardToFreeSwitch` 类似，但**无故障转移**，直接 `modifyHeadersForForwarding` + `sdpProcessor.process` 后发送。

#### 4.2.3 `forwardToWebSocketByUser(username, domain, message)` —— 转发到坐席 WebSocket

```text
sessionManager.getSessionIdByUser(username, domain) → sessionId
    ├── 不存在 → 抛 SipProxyException("未找到WebSocket会话")
    └── 存在   → modifyWsProxyHeaders(message) + sdpProcessor.process + toWebSocket(sessionId, modifiedMessage)
```

#### 4.2.4 `forwardToOutboundGateway(request, gatewayId)` —— 豁免场景直接出局

依据方案文档第 3.5 节「三个豁免场景」，该方法处理"FS 源 INVITE 携带 X-Gateway-Id"的快速出局：

```text
forwardToOutboundGateway(request, gatewayId)
    │
    ▼
[1] gatewayProvider.getGatewayById(gatewayId)
    ├── 不存在 → 抛 SipProxyException("网关不存在")
    │
    ▼
[2] modifyHeadersForForwarding(request, targetIp, targetPort, 0)  // 标准头域改写（Via/Contact）
    │
    ▼
[3] outboundGatewayRewriter.rewrite(request, gateway)  // 出局信令改写（委托扩展点）
    │
    ▼
[4] sdpProcessor.process(modifiedMessage)
    │
    ▼
[5] 缓存 originalInviteText + thirdPartyNode 到 SessionInfo（用于 407 还原和 Response 来源识别）
    │
    ▼
[6] 按 toSipTransport 选 UDP/TCP SipProvider → sendRequest(request)
```

#### 4.2.5 `handle407ProxyAuth(response, sessionInfo)` —— 407 鉴权处理

委托 `GatewayAuthManager.handle407Challenge`，详见 4.3 节。

#### 4.2.6 `modifyHeadersForForwarding(message, targetIp, targetPort, attemptCount)` —— 标准头域改写

依据方案文档"代理转发消息时 Contact/Via 需替换为 SIP 代理公网地址"的要求：

```text
[1] 按 Call-ID 查 SessionInfo（无则跳过改写）
[2] 读取 sipProxyProperties.sip.publicIp / publicPort（SIP 代理公网地址）
[3] 替换 Contact 头为 sipProxyPublicIp:publicPort;transport=xxx
[4] Request 分支：
    ├── 替换 Via 头为 sipProxyPublicIp:publicPort;transport=xxx;branch=branchId;rport
    └── 修改 Request-URI 为 targetIp:targetPort
[5] Response 分支：
    └── 替换 Via 头为 targetIp:targetPort（响应回送到来源节点）
[6] validateIceCandidateInSdp：校验 SDP ICE 候选完整性（含 ice-ufrag 但无 candidate 时 warn）
```

#### 4.2.7 `modifyWsProxyHeaders(message)` —— WebSocket 代理头改写

专门处理转发到坐席 WebSocket 的消息：

```text
[1] 按 Call-ID 查 SessionInfo
[2] 替换 Contact 头为 localIpAddress:sipPort;transport=ws
[3] Request 分支：
    ├── 替换 Via 头为 localIpAddress:sipPort;transport=ws;branch=branchId;rport
    └── 修改 Request-URI：
        ├── sessionInfo.websocketContactIp 非空 → 使用 sessionInfo 中缓存的 WebSocket Contact
        └── 为空 → 使用原始 Request-URI 的 user 部分 + localIpAddress:sipPort;transport=ws
[4] modifySdpForWebSocket：替换 SDP c=IN IP4 为 FS 公网 IP（WebRTC SDP 改写）
```

### 4.3 GatewayAuthManager（网关鉴权管理器）

**类职责**：`@Component`，统一管理 SIP 网关 Digest 鉴权，从 SipMessageForwarder 剥离 407 处理职责。支持 RFC 2617 标准 Digest + qop=auth 增强 + stale 重挑战。

**常量**：`MAX_AUTH_CHALLENGE_COUNT = 2`

**字段依赖**：`GatewayProvider`、`SipSessionManager`、`SipNodeManager` + JAIN-SIP 工厂（由 `init(headerFactory, addressFactory, sipProvider, sipProviderTcp)` 注入）

**关键方法**：

#### 4.3.1 `handle407Challenge(response, sessionInfo)` —— 407 处理主流程

```text
[1] 循环防护 canRetry：
    ├── count=0 → 允许
    ├── count=1 且 stale=true 且 nonce 更新 → 允许
    └── 其他 → 拒绝（返回 false，正常转发 407）
    │
    ▼
[2] 解析 Proxy-Authenticate 头：realm / nonce / qop / algorithm / stale
    │
    ▼
[3] 查 GatewayInfo 校验：
    ├── gatewayId 为空 → 拒绝
    ├── 网关不存在或已禁用 → 拒绝
    ├── authType != 1 → 拒绝
    └── username/password 为空 → 拒绝
    │
    ▼
[4] 从 sessionInfo.originalInviteText 还原 INVITE
    │
    ▼
[5] 计算 Digest：
    ├── qop=auth 模式：MD5(HA1:nonce:nc:cnonce:auth:HA2)
    │   └── nc = String.format("%08x", challengeCount+1)
    │   └── cnonce = UUID.substring(0,16)
    └── 无 qop 模式：MD5(HA1:nonce:HA2)
        └── HA1 = MD5(username:realm:password)
        └── HA2 = MD5(method:uri)
    │
    ▼
[6] 注入 Proxy-Authorization 头（含 username/realm/nonce/uri/response/algorithm/qop/nc/cnonce）
    │
    ▼
[7] CSeq 序列号 +1，Via branch 重新生成
    │
    ▼
[8] 缓存 SessionInfo（authChallengeCount+1, last407Nonce=nonce）
    │
    ▼
[9] 按 transport 选择 SipProvider 重发 INVITE
```

#### 4.3.2 `canRetry(challengeCount, stale, currentNonce, lastNonce, callId)` —— 循环防护

| challengeCount | stale | nonce 更新 | 结果 |
| --- | --- | --- | --- |
| 0 | - | - | 允许 |
| 1 | true | 是 | 允许（重挑战场景） |
| 其他 | - | - | 拒绝 |

### 4.4 SipNodeManager（节点管理器）

**类职责**：`@Component`，管理 FreeSWITCH 节点与第三方节点的选择、缓存、备用节点切换。通过 `FsNodeProvider` + `GatewayProvider` 扩展点获取节点列表，注入 `StringRedisTemplate` + `ObjectMapper`。

#### 4.4.1 FS 节点选择 `selectFreeSwitchNode(callId)`

依据方案文档 4.2 节「FreeSWITCH 无状态化与故障切换」要求实现：

```text
[1] getOnlineFsNodes()：从 fsNodeProvider.listFsNodes() 获取节点列表，过滤在线节点
    ├── 为空 → 返回 null
    │
    ▼
[2] getSessionNode(callId)：优先返回会话已绑定的 FS 节点
    ├── 已缓存且节点仍在线 → 返回缓存节点（保证 BYE/会话内方法转发到同一 FS）
    └── 已缓存但离线 → removeSessionNode(callId)，继续选新节点
    │
    ▼
[3] 一致性哈希选择：
    hashCode = callId.hashCode()
    nodeIndex = Math.abs(hashCode) % onlineNodes.size()
    selectedNode = onlineNodes.get(nodeIndex)
    │
    ▼
[4] cacheSessionNode(callId, selectedNode) → 缓存到 Redis（ipcc:sipproxy:session:fsnode:{callId}）
```

**设计意图**：使用 Call-ID 哈希而非随机选择，保证同一会话的 INVITE/BYE/PRACK 等请求始终路由到同一 FS，避免跨 FS 通道状态丢失。

#### 4.4.2 ViaPort 节点匹配 `selectFreeSwitchNodeByViaPort(callId, viaPort)`

**多 FS 实例场景专用**：当 FS originate 回注 sipproxy 时，需锚定到发起 originate 的同一 FS，否则 ESL 找不到对应通道。

```text
[1] 遍历在线 FS 节点，匹配 viaPort == node.sipPort
    ├── 匹配 → cacheSessionNode(callId, matchedNode) + 返回
    └── 不匹配 → fallback 到 selectFreeSwitchNode(callId)
```

**关键设计**：必须**先匹配再查缓存**，避免 hash 选择的错误节点覆盖正确节点。

#### 4.4.3 第三方节点选择 `selectThirdPartyNode(callId, sourceIp)`

```text
[1] 优先返回已缓存的会话第三方节点（getThirdPartySessionNode(callId)）
    │
    ▼
[2] getAllThirdPartyNodes()：gatewayProvider.listEnabledGateways()
    │
    ▼
[3] 按 sourceIp 精确匹配：
    遍历 allNodes，sourceIp.equals(node.address)
    ├── 匹配 → 选中
    └── 全部不匹配 → 返回 null（不 fallback）
    │
    ▼
[4] cacheThirdPartySessionNode(callId, selectedNode) → 缓存到 Redis
```

#### 4.4.4 备用节点选择 `selectAlternativeFreeSwitchNode(triedNodes, callId)`

```text
getOnlineFsNodes()
    │
    ▼
遍历在线节点，过滤已尝试节点（triedNodes）
    ├── 找到未尝试节点 → cacheSessionNode(callId, alternativeNode) + 返回
    └── 全部已尝试 → 返回 null
```

### 4.5 SipSessionManager（会话管理器）

**类职责**：`@Component`，统一使用 `SESSION_INFO_PREFIX` 管理 SessionInfo，并提供注册信息缓存。注入 `StringRedisTemplate` + `ObjectMapper`。

#### 4.5.1 SessionInfo 生命周期管理

| 方法                               | 触发点                                                                                                 | 作用                                                             |
| -------------------------------- | --------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `cacheSessionInfo(sessionInfo)`  | `WsInviteRequestHandler.doHandle()` / `SipInviteRequestHandler.handle()` 创建会话时                      | 序列化 SessionInfo 为 JSON，写入 `ipcc:sipproxy:session:info:{callId}`，TTL=120s |
| `getSessionInfo(callId)`         | 几乎所有 Handler 与 Forwarder 方法                                                                         | 从 Redis 反序列化 SessionInfo                                       |
| `updateSessionInfo(sessionInfo)` | `SipProxyService.handleWebSocketSipMessage()` 更新 Contact 后；`SipDefaultRequestHandler.handle()` 刷新会话 | 直接调用 cacheSessionInfo 覆盖写入                                     |

#### 4.5.2 注册信息管理

- `cacheRegisterInfo(sessionId, username, domain)`：`WsRegisterRequestHandler` 校验通过后调用，写入两个 Key：
  - `ipcc:sipproxy:session:register:{sessionId}` = `username:domain`（TTL=3600s，sessionId → 用户反查）
  - `ipcc:sipproxy:user:session:{username}:{domain}` = `sessionId`（TTL=3600s，用户 → sessionId 反查，供 `forwardToWebSocketByUser` 使用）
- `getSessionIdByUser(username, domain)`：查 `ipcc:sipproxy:user:session:{username}:{domain}`，返回 sessionId
- `cleanupRegisterInfo(sessionId)`：WebSocket 连接关闭时由 `SipProxyService.cleanupRegisterInfo()` 调用，反查注册信息后删除两个 Key

### 4.6 ResponseForwardingStrategy（响应转发策略）

**类职责**：`@Component`，维护 `source × callType → target` 三维映射表，提供 `getForwardingTarget(source, callType)` 决策方法。

**策略矩阵**见 3.3 节表格。

**决策逻辑**：

```text
getForwardingTarget(source, callType)
    │
    ▼
forwardingStrategy.get(source) → sourceStrategy
    ├── 不存在 → warn + 返回 FREESWITCH（兜底）
    │
    ▼
sourceStrategy.get(callType) → target
    └── 不存在 → warn + 返回 FREESWITCH（兜底）
```

**复用点**：

- `UnifiedResponseHandler.determineResponseTarget()`：响应转发目标决策
- `SipDefaultRequestHandler.handle()`：会话内方法（PRACK/UPDATE/INFO）转发目标决策
- `WsDefaultRequestHandler.doHandle()`：WS 来源会话内方法转发目标决策

### 4.7 SipAnalysisUtil（SIP 分析工具）

**类职责**：纯静态工具类，SIP 文本/对象解析、头域提取、响应构造。**静态初始化块**创建独立 SipStack（STACK_NAME=`SipAnalysisUtilStack`，仅用于解析，不实际运行）+ MessageFactory/HeaderFactory/AddressFactory。

**关键工具方法**：

| 方法                                                            | 用途                                                             |
| ------------------------------------------------------------- | -------------------------------------------------------------- |
| `parseSipMessage(sipText)`                                    | 将 SIP 文本解析为 JAIN SIP Message（Request/Response）                 |
| `parseSipMessageRequest(sipText)` / `parseSipMessageResponse(sipText)` | 精确解析为 Request / Response                                      |
| `isSipMessage(text)` / `isRequest(text)` / `isResponse(text)` | 判断 SIP 消息类型，正则匹配请求行/状态行                                        |
| `getCallId(message)`                                          | 提取 Call-ID 头值，所有 Handler 与 Forwarder 必用                        |
| `getBranch(message)`                                          | 提取 Via 头 branch 参数，用于 modifyHeadersForForwarding 重建 Via        |
| `getSourceIpFromMessage(message)`                             | 从 Via 头提取来源 IP（received 优先，无则 host），用于 selectThirdPartyNode 反查 |
| `getSourcePortFromMessage(message)`                           | 从 Via 头提取来源端口（rport 优先 → sent-by port → 默认 5060）                |
| `getTransportFromVia(message)`                                | 从 Via 头提取 transport（UDP/TCP，默认 udp），用于选择 SipProvider            |
| `extractToUser(request)` / `extractToDomain(request)`         | 提取 To 头用户名/域名（domain 带 port，格式 `host:port`）                    |
| `extractFromUser(request)` / `extractFromDomain(request)`     | 提取 From 头用户名/域名                                               |
| `extractContact(message)`                                     | 提取 Contact 头 SipURI，用于更新 SessionInfo 的 websocketContactXxx     |
| `buildResponse(request, statusCode)`                          | 基于请求构造响应（JAIN SIP API 自动填充标准原因短语）                              |
| `getAuthorization(message)`                                   | 提取 Authorization 头，用于 REGISTER Digest 鉴权                       |
| `modifyFrom(message, newFrom)` / `modifyTo(message, newTo)`   | 修改 From/To 头                                                  |

***

## 五、SIP 方法处理详解

### 5.1 INVITE 处理

#### 5.1.1 坐席发起（WsInviteRequestHandler）

**触发场景**：方案文档场景一（内部呼叫）、场景二（出局呼叫）的第一段 INVITE。

**处理逻辑**：

1. **入口设置 traceId**：`TraceContext.setTraceId(callId)`（可选扩展点），缺失时回退到 UUID，全链路日志串联
2. **re-INVITE 检测**：Call-ID 已存在 SessionInfo 且有 freeSwitchNode → 直接 `forwardToFreeSwitch` 到已有 FS（hold/unhold/Session Timer 刷新场景）
3. **提取 From/To 头信息**：`SipAnalysisUtil.extractFromUser/extractToUser` 等
4. **发送 100 Trying**：`sendTryingResponse(sessionId, request)`，通过 `messageForwarder.toWebSocket` 发回坐席
5. **选择 FS 节点**：`nodeManager.selectFreeSwitchNode(callId)`，按一致性哈希 + 会话绑定，无则抛 `SipProxyException(NO_AVAILABLE_FS_NODE)`
6. **构造 SessionInfo**：
   - `sessionId` = WebSocket 会话 ID
   - `freeSwitchNode` = 选定 FS
   - `callType` = **`OUTBOUND`**（统一标记，不区分内部/外呼，由 ESL 层走呼出号码路由匹配）
   - `websocketContactName/Ip/Port/Transport` = 从 Contact 头提取
   - `gatewayId` = `extractGatewayId(request)`（如 INVITE 头携带 X-Gateway-Id）
7. **缓存 SessionInfo**：`sessionManager.cacheSessionInfo(sessionInfo)`
8. **转发到 FS park**：`messageForwarder.forwardToFreeSwitch(request, freeSwitchNode)`

**关键点**：

- callType 统一标记为 `OUTBOUND`，不根据被叫是否是注册用户区分内部/外呼，符合方案文档 3.1 节「所有呼叫必须走号码路由 + IVR」要求
- `X-Gateway-Id` 仅作为 IVR 转接节点的网关覆盖项保存到 SessionInfo，不再用于路由决策（方案文档 3.2 节）

#### 5.1.2 第三方/FS 发起（SipInviteRequestHandler）

**触发场景**：

- 方案文档场景三（入局呼叫）：第三方网关 → sipproxy
- 方案文档场景一/二/三的第二段 INVITE：FS → sipproxy（ESL originate 回注）
- 方案文档场景四/五/六/七的豁免场景：FS → sipproxy（携带 X-Gateway-Id）

**处理逻辑**：

1. **入口设置 traceId**
2. **提取 To/From 头信息并校验**：失败返回 BAD\_REQUEST
3. **提取 X-Gateway-Id 头**：`extractGatewayId(request)`
4. **查/建 SessionInfo**：
   - 已存在：warn 日志
   - 不存在：
     - 设置 `toSipTransport` = `getTransportFromVia(request)`
     - **callType 标记规则**：
       - `FREESWITCH + gatewayId 非空` → **`OUTBOUND`**（c-leg 出局腿，响应需回送 FS）
       - `FREESWITCH + gatewayId 为空` → **`INTERNAL`**（FS 内部回环，如 REFER 内部转接）
       - `THIRD_PARTY` → **`INBOUND`**（按 sourceIp 反查 thirdPartyNode 并缓存到 SessionInfo）
       - 其他 → **`INTERNAL`**
     - `selectFreeSwitchNode(callId)` 选 FS 节点并保存到 SessionInfo
     - 保存 `gatewayId` 到 SessionInfo
     - `cacheSessionInfo()`
5. **豁免分支**（关键）：`FREESWITCH.equals(source) && StrUtil.isNotBlank(gatewayId)`
   - 调用 `messageForwarder.forwardToOutboundGateway(request, gatewayId)` → 直接出局改写
   - **跳过 FS park + 号码路由 + IVR 流程**
   - 适用方案文档场景四/五/六/七
6. **快速推 WebSocket 分支**：`FREESWITCH.equals(source) && StrUtil.isBlank(gatewayId)` + 被叫是已注册坐席
   - `agentInfoProvider.getAgent(toUser, null)` 非 null → 已注册 JsSIP 坐席
   - `selectFreeSwitchNodeByViaPort(callId, viaPort)` 按 Via 端口覆盖 freeSwitchNode
   - `messageForwarder.forwardToWebSocketByUser(toUser, agentDomain, request)` 直接推送
   - **避免 FS originate→sipproxy→FS park 死循环，绕过 FS WebRTC SDP 协商失败**
7. **默认分支**：`forwardToFreeSwitch(request, freeSwitchNode)` → FS park，由 ESL 处理器走号码路由匹配 → IVR 流程

### 5.2 BYE 处理（B2BUA 两段挂断）

依据方案文档 5.4 节「BYE 挂断流程」与 B2BUA 角色定位，BYE 在两段对话中分别独立处理。

#### 5.2.1 坐席挂断（WsByeRequestHandler）

**触发场景**：坐席通过 JsSIP 主动挂断通话。

**处理逻辑**：

1. `nodeManager.selectFreeSwitchNode(callId)` 选择会话绑定的 FS 节点
   - **关键**：`selectFreeSwitchNode` 优先返回 `getSessionNode(callId)` 缓存的节点，保证 BYE 发到 INVITE 锚定的同一 FS
2. `messageForwarder.forwardToFreeSwitch(request, freeSwitchNode)` 转发 BYE 到 FS

**说明**：

- 本处理器负责"坐席→FS"段 BYE 转发
- FS 收到 BYE 后拆除媒体通道，触发 `CHANNEL_HANGUP_COMPLETE` 事件
- CallInfo/会话信息清理由 ESL 事件处理器统一完成，sipproxy 的 BYE 处理器不参与清理

#### 5.2.2 FS/第三方挂断（SipByeRequestHandler）

**触发场景**：FS 或第三方网关发起 BYE（对端挂断后通知坐席）。

**处理逻辑**：

1. 提取 To 头信息并校验（To 头指向被挂断的坐席分机）
2. `forwardRequestByRegistration(request, callId, toUser, toDomain)`：
   - 已注册坐席 → `forwardToWebSocketByUser(toUser, toDomain, request)` 转发到坐席 WebSocket
   - 未注册 → `selectThirdPartyNode(callId, sourceIp)` + `forwardToThirdParty` 转发到第三方 SIP

**说明**：

- 本处理器负责"FS→坐席"段 BYE 转发
- 两段 BYE 相互独立，FS 与坐席端各自处理媒体释放
- `source` 参数（FREESWITCH/THIRD\_PARTY）不参与转发决策，统一按 To 头注册状态决定转发目标

### 5.3 REFER 处理（转接）

依据方案文档第十一章「场景六：坐席间通话中转接到外部手机」实现。

#### 5.3.1 解耦设计

sipproxy 模块**不再连接 FreeSWITCH**，所有 ESL 编排（originate/bridge/hold/kill）通过 `SipMessageInterceptor` 扩展点委托父程序实现。

- 父程序实现 `SipMessageInterceptor.preWsToSip(request)` 返回 `true` 表示接管 ESL 编排
- 父程序未实现或返回 `false` 时，sipproxy 按 `forwardToFreeSwitchByDefault` 透明转发到 FS

#### 5.3.2 doHandle 主流程

1. **设置 traceId**
2. **解析 REFER 头**：
   - `extractReferTo(request)`：从 `Refer-To: <sip:number@domain>` 提取目标号码
   - `extractHeader(request, "X-Gateway-Id")`：提取网关 ID
   - `extractHeader(request, "X-Transfer-Type")`：提取转接类型（attended/blind，默认 blind）
3. **校验 Refer-To 非空**：失败返回 BAD_REQUEST
4. **检测 SipMessageInterceptor 扩展点**：
   - 存在且 `preWsToSip(request)` 返回 true → 父程序已接管，回复 202 Accepted 后返回
   - 不存在或返回 false → `forwardToFreeSwitchByDefault(request, callId)` 透明转发到 FS
5. **回复 202 Accepted**（RFC 3515 要求）

### 5.4 REGISTER 处理（坐席注册）

依据方案文档第二章「所有坐席均注册在 SIP 代理服务上」实现。

#### 5.4.1 doHandle 主流程

1. **Digest 鉴权流程**：
   - `getAuthorization(request)` 提取 Authorization 头
   - 无 Authorization 头 → `send401Response`（带 `WWW-Authenticate: Digest realm, nonce`）
   - 有 Authorization 头 → 进入校验
2. **校验委托 SipAuthenticationProvider 扩展点**：
   - 默认 `DefaultSipAuthenticationProvider` 本地 Digest 校验：
     - 查 `AgentInfoProvider.getAgent(username, realm)` 获取坐席
     - 坐席不存在 → 返回 false
     - 计算 `HA1 = MD5(username:realm:password)`，`HA2 = MD5(method:uri)`
     - `expectedResponse = MD5(HA1:nonce:HA2)`
     - 比对 `expectedResponse.equals(response)`
3. **响应**：
   - 校验通过 → `send200OkResponse`（带回 Contact 头）+ `sessionManager.cacheRegisterInfo(sessionId, username, realm)` + `AuthenticationCallback.onSuccess`
   - 校验失败 → `send403Response` + `AuthenticationCallback.onFailure`

#### 5.4.2 注册信息缓存

`cacheRegisterInfo(sessionId, username, realm)` 写入两个 Redis Key：

- `ipcc:sipproxy:session:register:{sessionId}` = `username:realm`（TTL=3600s）
- `ipcc:sipproxy:user:session:{username}:{realm}` = `sessionId`（TTL=3600s）

### 5.5 OPTIONS 处理（心跳保活）

`WsOptionsRequestHandler.doHandle` 构造 200 OK 响应：

- 添加 `Allow` 头：`SipProxyConstants.SIP_METHODS_SUPPORTED`（"INVITE, ACK, CANCEL, BYE, REGISTER, OPTIONS, PRACK, SUBSCRIBE, NOTIFY, PUBLISH, INFO, REFER, MESSAGE, UPDATE"）
- 添加 `Content-Length: 0`
- 通过 `messageForwarder.toWebSocket(sessionId, response)` 发回坐席

### 5.6 PRACK/UPDATE/INFO 等会话内方法（SipDefaultRequestHandler / WsDefaultRequestHandler）

依据方案文档 M3「PRACK / UPDATE 专门处理」已修复项实现，是 sipproxy 模块的关键改造点。

#### 5.6.1 改造前问题

原逻辑按 To 头查注册用户转发，无法正确处理 FS↔第三方网关的会话内方法（例如第三方网关发送的 UPDATE 到达时，To 头指向被叫分机，但实际应转发回 FS）。

#### 5.6.2 改造后逻辑（SIP 来源）

```text
handle(request, callId, source)
    │
    ▼
[1] Call-ID 缺失 → fallbackForwardByRegistration（按 To 头查注册转发）
    │
    ▼
[2] 提取 To 头校验 → 失败返回 BAD_REQUEST
    │
    ▼
[3] 按 Call-ID 查 SessionInfo：
    ├── 不存在 → forwardRequestByRegistration（fallback 按注册转发，向后兼容）
    └── 存在 → updateSessionInfo 刷新会话
        │
        ▼
[4] responseForwardingStrategy.getForwardingTarget(source, callType) → target
        │
        ▼
[5] forwardByTarget：
        ├── WEBSOCKET   → modifyWsProxyHeaders(request) + forwardToWebSocket(sessionId)
        ├── FREESWITCH  → forwardToFreeSwitch(freeSwitchNode)
        ├── THIRD_PARTY → forwardToThirdParty(thirdPartyNode)
        └── 未知        → fallback forwardRequestByRegistration
```

#### 5.6.3 改造后逻辑（WS 来源）

`WsDefaultRequestHandler.doHandle` 与 SIP 来源对称：

```text
[1] Call-ID 缺失 → fallbackToFreeSwitch
    │
    ▼
[2] 查 SessionInfo：
    ├── 不存在 → fallbackToFreeSwitch
    └── 存在 → 按 source=WEBSOCKET + callType 决策
        │
        ▼
[3] 按 target 分支：
    ├── FREESWITCH  → forwardToFreeSwitchBySession
    ├── THIRD_PARTY → forwardToThirdPartyBySession
    ├── WEBSOCKET   → warn 回环 + fallback
    └── 未知        → fallback
```

#### 5.6.4 关键设计点

- **复用 ResponseForwardingStrategy**：与会话内响应转发共享同一策略表，保证 source × callType → target 决策一致
- **SIP 头原样透传**：未对 Require/RSeq/RAck 等 100rel 相关头域做特殊处理，自动保留（方案文档 17.2 节"透传 Require: 100rel / RSeq / RAck 头域"要求）
- **SessionInfo 刷新**：会话已建立时调用 `updateSessionInfo` 刷新会话，避免被 Redis TTL 淘汰清理
- **Fallback 兜底**：Call-ID 缺失或 SessionInfo 不存在时，fallback 到按 To 头查注册转发，保证未走 sipproxy 完整建立的会话向后兼容

***

## 六、会话与状态管理

### 6.1 SessionInfo 生命周期

```text
[创建]  WsInviteRequestHandler.doHandle()
        或  SipInviteRequestHandler.handle()
        │
        │  new SessionInfo(callId)
        │  set sessionId/freeSwitchNode/callType/websocketContactXxx/gatewayId
        │  sessionManager.cacheSessionInfo(sessionInfo)  // 写入 Redis
        │
        ▼
[更新]  1. SipProxyService.handleWebSocketSipMessage()
           收到 WS 消息时若 SessionInfo 存在，更新 websocketContactXxx
        2. SipDefaultRequestHandler.handle()
           会话内方法到达时刷新会话（updateSessionInfo）
        3. GatewayAuthManager.handle407Challenge()
           407 鉴权时更新 authChallengeCount/last407Nonce
        4. SipMessageForwarder.forwardToOutboundGateway()
           出局时缓存 originalInviteText/thirdPartyNode
        │
        ▼
[查询]  几乎所有 Handler 与 Forwarder 方法
        sessionManager.getSessionInfo(callId)
        │
        ▼
[销毁]  sipproxy 模块不主动销毁 SessionInfo
        依赖 Redis TTL=120s 自动过期
        实际清理由 ESL 层 CHANNEL_HANGUP_COMPLETE 事件处理器
        （在 sipproxy 模块外）
```

**说明**：

- SessionInfo 的销毁由 ESL 层 `CHANNEL_HANGUP_COMPLETE` 事件处理器统一完成，sipproxy 的 BYE 处理器不参与清理（依据方案文档 5.4 节）
- TTL=120s 的设计保证会话活跃期间不会被淘汰，会话内方法到达时还会 `updateSessionInfo` 刷新 TTL

### 6.2 节点绑定策略

#### 6.2.1 FS 节点绑定

```text
[写入]  WsInviteRequestHandler.doHandle()
        或  SipInviteRequestHandler.handle()
        │
        │  selectFreeSwitchNode(callId)
        │    └── cacheSessionNode(callId, selectedNode)  // 写入 ipcc:sipproxy:session:fsnode:{callId}
        │
        ▼
[读取]  1. selectFreeSwitchNode(callId)  // 同一会话后续 BYE/PRACK 等请求
           └── getSessionNode(callId) → 已缓存节点（若仍在线）
        2. selectFreeSwitchNodeByViaPort(callId, viaPort)  // 多 FS 实例场景
           └── 按 Via 端口匹配 FS SIP 监听端口
```

**一致性保证**：使用 Call-ID 哈希 + Redis 缓存双重保证同一会话的所有请求路由到同一 FS。

#### 6.2.2 第三方节点绑定

```text
[写入]  SipInviteRequestHandler.handle()
        （THIRD_PARTY 来源 INVITE 时）
        或  SipMessageForwarder.forwardToOutboundGateway()
        （豁免场景出局时缓存 thirdPartyNode 用于 Response 来源识别）
        │
        │  selectThirdPartyNode(callId, sourceIp)
        │    └── cacheThirdPartySessionNode(callId, selectedNode)  // 写入 ipcc:sipproxy:session:thirdparty:{callId}
        │
        ▼
[读取]  1. selectThirdPartyNode(callId, sourceIp)  // 同一会话后续请求
           └── getThirdPartySessionNode(callId) → 已缓存节点
        2. UnifiedResponseHandler.forwardToThirdParty
           └── sessionInfo.getThirdPartyNode()  // 响应回送到来源第三方网关
```

**响应回送保证**：第三方网关入局时按 sourceIp 反查并缓存节点，后续响应（如 200 OK）通过 SessionInfo.thirdPartyNode 回送到同一网关，避免多网关场景下响应错送。

### 6.3 Redis 缓存结构

sipproxy 模块使用的 Redis Key 完整清单：

| Redis Key                              | 数据结构         | 写入点                                         | 读取点                                                                               | TTL  |
| -------------------------------------- | ------------ | ------------------------------------------- | --------------------------------------------------------------------------------- | ---- |
| `ipcc:sipproxy:session:info:{callId}`            | String（JSON） | `SipSessionManager.cacheSessionInfo`        | `getSessionInfo`（所有 Handler/Forwarder）                                            | 120s |
| `ipcc:sipproxy:session:register:{sessionId}`     | String       | `SipSessionManager.cacheRegisterInfo`       | `cleanupRegisterInfo`（WebSocket 关闭时反查）                                            | 3600s |
| `ipcc:sipproxy:user:session:{username}:{domain}` | String       | `SipSessionManager.cacheRegisterInfo`       | `getSessionIdByUser`（`forwardToWebSocketByUser`、`isAgentOnline`）                  | 3600s |
| `ipcc:sipproxy:session:fsnode:{callId}`          | String（JSON） | `SipNodeManager.cacheSessionNode`           | `getSessionNode`（`selectFreeSwitchNode`、`selectFreeSwitchNodeByViaPort`）         | 120s |
| `ipcc:sipproxy:session:thirdparty:{callId}`      | String（JSON） | `SipNodeManager.cacheThirdPartySessionNode` | `getThirdPartySessionNode`（`selectThirdPartyNode`）                                | 120s |
| `ipcc:sipproxy:message:record:{...}`             | -            | 已定义但本模块未使用                                  | -                                                                                 | -    |

***

## 七、WebSocket 模块详解

### 7.1 SipWebSocketHandler（WebSocket 处理器）

**类职责**：继承 `TextWebSocketHandler`，处理 WebSocket 连接生命周期与 SIP 消息分发。构造注入 `SipProxyService`、`WsSessionManager`、`SipFrameReassembler`。

**生命周期回调**：
- `afterConnectionEstablished`：`wsSessionManager.register(session)`
- `handleTextMessage`：更新 `lastActiveAt` → `sipFrameReassembler.reassemble` 循环提取完整 SIP 消息 → 逐条 `sipProxyService.handleWebSocketSipMessage`（单条失败不影响后续）
- `afterConnectionClosed`：`unregister` + `sipFrameReassembler.cleanup` + `sipProxyService.cleanupRegisterInfo`
- `handleTransportError`：仅日志

### 7.2 SipHandshakeInterceptor（握手拦截器）

**类职责**：实现 `HandshakeInterceptor`，负责 token 校验与 RFC 7118 SIP 子协议协商。构造注入 `SipProxyProperties` + 可选 `WsHandshakeAuthenticator`。

**`beforeHandshake` 流程**：

```text
[1] 提取 token（ServletServerHttpRequest.getParameter 优先，非 Servlet 环境 fallback 到 URI query 解析）
    │
    ▼
[2] token 为空：
    ├── requireAuth=true → 拒绝握手
    └── requireAuth=false → 跳过校验（本地调试模式）
    │
    ▼
[3] token 非空 + requireAuth=true + wsHandshakeAuthenticator 存在
    └── 调用 authenticate(token, remoteIp, headers)
    │
    ▼
[4] RFC 7118 SIP 子协议协商（handleSipSubProtocol）
    └── 响应 Sec-WebSocket-Protocol: sip 头
    （缺失会导致 JsSIP 客户端 EOFException + CloseStatus 1006）
    │
    ▼
[5] 写入 token + lastActiveAt 到 attributes
```

### 7.3 SipFrameReassembler（消息分片重组）

**类职责**：SIP 消息分片重组，处理单帧多消息（pipelining）和单消息多帧（分片）。

**核心逻辑**：
- 每会话独立缓冲区（`ConcurrentHashMap<sessionId, StringBuilder>`）
- **缓冲区上限 1MB**，超过清空并返回空列表（防恶意客户端撑爆内存）
- 循环检测 `\r\n\r\n` 头部结束标记，按 Content-Length 提取完整消息

### 7.4 LocalWsSessionManager（本地会话管理器）

**类职责**：`WsSessionManager` 默认实现，单实例部署。`ConcurrentHashMap` 维护 `sessionId→WebSocketSession` 和 `username:domain→sessionId` 双向映射。

**关键方法**：`send`、`getSessionIdByUser`、`register`（初始化 lastActiveAt）、`unregister`（清理反向映射）、`getAllSessions`、`registerUser`

### 7.5 ZombieSessionCleaner（僵尸会话清理器）

**类职责**：定时清理僵尸 WS 会话。`@Scheduled(fixedDelay = 60_000L)`。

**逻辑**：遍历 `getAllSessions`，对 `lastActiveAt` 与当前时间差 > `idleTimeout*1000ms` 的会话：`session.close(CloseStatus.POLICY_VIOLATION)` + `cleanupRegisterInfo`。

**触发场景**：客户端异常断开（onClose 未触发）、JsSIP 停止心跳但 TCP 未关闭。

***

## 八、集群广播模块详解

### 8.1 WsMessageSender（消息发送器接口）

**类职责**：集群广播消息发送接口，5 种实现：

| 实现 | 触发条件 | 状态 |
| --- | --- | --- |
| `LocalWsMessageSender` | `sender-type=local`（默认） | 完整实现，单实例部署 |
| `RedisWsMessageSender` | `sender-type=redis` + `StringRedisTemplate` 在 classpath | 完整实现，Redis pub/sub |
| `KafkaWsMessageSender` | `sender-type=kafka` + `spring-kafka` 在 classpath | 占位骨架，TODO |
| `RabbitMqWsMessageSender` | `sender-type=rabbitmq` + `spring-rabbit` 在 classpath | 占位骨架，TODO |
| `RocketMqWsMessageSender` | `sender-type=rocketmq` + `rocketmq-spring-boot-starter` 在 classpath | 占位骨架，TODO |

### 8.2 SipWsBroadcastMessage（广播消息载体）

**字段**：
- `targetType`：USER / SESSION / ALL
- `target`：USER 时为 `username:domain`，SESSION 时为 sessionId
- `message`：SIP 消息文本
- `sourceInstance`：发送方实例 ID（避免循环广播）
- `timestamp`：时间戳

### 8.3 RedisWsMessageSender（Redis 实现）

**关键方法**：
- `registerListener(RedisMessageListenerContainer)`：通过 `MessageListenerAdapter` 反射调用 `onMessage`，订阅 `senderRedisChannel`
- `onMessage(message, pattern)`：反序列化 JSON → 忽略 `sourceInstance` == 自身 `instanceId` 的消息 → 回调
- `send`：设置 `sourceInstance` + `timestamp` → Jackson 序列化 → `redisTemplate.convertAndSend(channel, json)`

### 8.4 ClusterBroadcastConsumer（集群消费者）

**类职责**：集群广播消费者。构造注入 `WsMessageSender` + `WsSessionManager`，`init()` 注册 `onReceive(this::onBroadcast)` 回调。

**`onBroadcast` 处理逻辑**：按 `targetType` 分发：
- USER：按 `username:domain` 查本实例 sessionId，持有则 `send`
- SESSION：直接 `wsSessionManager.send(target, message)`
- ALL：遍历 `getAllSessions` 全量发送

不持有则忽略（其他实例会处理）。

***

## 九、扩展点 API 详解

### 9.1 扩展点清单

模块通过 13 个扩展点接口（`api/` 包）与父程序解耦，所有接口在 `defaults/` 包有默认实现，通过 `@ConditionalOnMissingBean` 条件注册，父程序实现对应接口并注册为 Bean 即可覆盖。

| 接口 | 方法 | 默认实现 | 用途 |
| --- | --- | --- | --- |
| `AgentInfoProvider` | `getAgent(extension, domain): AgentInfo` | `DefaultAgentInfoProvider`（返回 null） | 坐席信息查询 |
| `FsNodeProvider` | `listFsNodes(): List<FsNodeInfo>` | `DefaultFsNodeProvider`（空列表） | 在线 FS 节点列表 |
| `GatewayProvider` | `getGatewayById` / `getGatewayByAddress` / `listEnabledGateways` | `DefaultGatewayProvider`（null/null/空列表） | 网关查询 |
| `MessageSourceIdentifier` | `identifySource(Message): String` | `DefaultMessageSourceIdentifier`（6 层递进） | 消息来源识别 |
| `OutboundGatewayRewriter` | `rewrite(Request, GatewayInfo)` | `DefaultOutboundGatewayRewriter`（3 步改写） | 出局 INVITE 头域改写 |
| `SdpProcessor` | `process(Message): Message` | `DefaultSdpProcessor`（透传） | SDP 媒体协商 |
| `IpWhitelist` | `isAllowed(ip): boolean` | `DefaultIpWhitelist`（全部放行） | IP 白名单 |
| `SipRateLimiter` | `tryAcquire(sourceIp, method): boolean` | `DefaultSipRateLimiter`（全部放行） | SIP 限流（429） |
| `SipAuthenticationProvider` | `authenticate(extension, domain, nonce, uri, response, method): boolean` | `DefaultSipAuthenticationProvider`（本地 Digest 校验） | SIP Digest 认证 |
| `WsHandshakeAuthenticator` | `authenticate(token, remoteIp, headers): boolean` | `DefaultWsHandshakeAuthenticator`（全部放行） | WS 握手 token 认证 |
| `AuthenticationCallback` | `onSuccess` / `onFailure` | `NoopAuthenticationCallback`（日志） | 认证事件回调 |
| `SipMessageInterceptor` | `preWsToSip(Message)` / `preSipToWs(Message)` | `NoopSipMessageInterceptor`（不拦截） | **REFER ESL 编排委托** |
| `TraceContext` | `setTraceId` / `getTraceId` | `DefaultTraceContext`（ThreadLocal） | 链路追踪 |
| `SipMessageTransport` | `send(Message, host, port, transport)` | `DefaultSipMessageTransport`（空实现） | 自定义传输 |

### 9.2 DefaultMessageSourceIdentifier 6 层递进识别

1. **X-FS-Source 头**（最高优先级）：自有 FS originate 注入的自定义头，区分同 IP 下 FS 与第三方网关
2. **坐席记录匹配**（From 头 extension+domain）：`AgentInfoProvider.getAgent`，存在 → `WEBSOCKET`。**主判断逻辑**，兼容 JsSIP/WebRTC/软电话/硬电话等任意客户端类型。domain 剥离端口（`stripPortFromHost`）
3. **JsSIP UA 兜底**：UA 含 `IPCC_JSSIP` → `WEBSOCKET`
4. **FS 节点 IP+端口精确匹配**：遍历 `fsNodeProvider.listFsNodes`，IP 相等 + 端口相等（或节点 port=null）→ `FREESWITCH`
5. **第三方网关 IP 匹配**（忽略端口）：遍历 `gatewayProvider.listEnabledGateways`，`sourceIp.equals(gateway.address)` → `THIRD_PARTY`
6. **FS UA 兜底**：UA 含 `FREESWITCH` → `FREESWITCH`
7. **兜底**：`WEBSOCKET`

### 9.3 DefaultOutboundGatewayRewriter（默认出局改写）

3 步改写：
1. 改写 From 头：`callerIdInFrom=0` → 原始主叫号码；`callerIdInFrom=1`（默认）→ `gateway.externalLineNumber`（DID）
2. 注入 P-Asserted-Identity 头：`<sip:originalCaller@fromDomain>`（运营商鉴权用）
3. 移除 Record-Route 头（拓扑隐藏）

### 9.4 DefaultSipAuthenticationProvider（默认认证）

本地 Digest 校验：
- 查 `AgentInfoProvider.getAgent(username, realm)` 获取坐席
- `HA1 = MD5(username:realm:password)`
- `HA2 = MD5(method:uri)`
- `expectedResponse = MD5(HA1:nonce:HA2)`
- 比对 `expectedResponse.equals(response)`

***

## 十、自动配置模块详解

### 10.1 SipProxyAutoConfiguration（主配置）

**类职责**：`@AutoConfiguration` + `@EnableConfigurationProperties(SipProxyProperties.class)` + `@ConditionalOnProperty(prefix="sipproxy", name="enabled", havingValue="true", matchIfMissing=true)`。

**核心服务通过 `@Service`/`@Component` 由 Spring 自动扫描，本配置类仅负责 13 个扩展点条件化注册**。所有默认实现均通过 `@Bean` + `@ConditionalOnMissingBean` 注册，父程序实现对应接口并注册为 Bean 即可覆盖。

### 10.2 SipProxyWebSocketAutoConfiguration（WebSocket 配置）

**类职责**：配置 JSR-356 WebSocket 容器与 SIP over WebSocket 接入链路。`@ConditionalOnProperty(prefix="sipproxy.websocket", name="enabled", havingValue="true", matchIfMissing=true)` + `@EnableWebSocket`。

**Bean 注册**：
- `sipProxyWebSocketContainer`：8KB 文本缓冲上限，`maxSessionIdleTimeout` = idleTimeout*1000ms
- `sipFrameReassembler`、`localWsSessionManager`（`@ConditionalOnMissingBean`）、`sipHandshakeInterceptor`
- `sipProxyWebSocketConfigurer`：通过 `ObjectProvider` 延迟获取 Handler，SipProxyService 未提供时跳过端点注册
- **内部配置类 `SipProxyServiceDependentConfiguration`**：`@ConditionalOnBean(SipProxyService.class)` 保护，注册 `sipWebSocketHandler` 和 `zombieSessionCleaner`

### 10.3 SipProxyClusterAutoConfiguration（集群配置）

**类职责**：根据 `sipproxy.cluster.sender-type` 选择 `WsMessageSender` 实现，`@EnableScheduling` 启用 `@Scheduled`。

**Bean 注册**：
- `localWsMessageSender`：`@ConditionalOnMissingBean` + `@ConditionalOnProperty(sender-type=local, matchIfMissing=true)`
- `sipProxyRedisMessageListenerContainer` + `redisWsMessageSender`：`@ConditionalOnClass(StringRedisTemplate)` + `@ConditionalOnProperty(sender-type=redis)`
- `clusterBroadcastConsumer`：`@ConditionalOnBean({WsMessageSender, WsSessionManager})`

### 10.4 SipProxySessionAutoConfiguration（会话配置）

**类职责**：声明会话存储对 Redis 的依赖。`@ConditionalOnClass(StringRedisTemplate)` + `@ConditionalOnProperty(prefix="sipproxy", name="enabled", havingValue="true", matchIfMissing=true)`。无 Bean 注册，仅构造时输出日志，fail-fast 提示 Redis 缺失。

### 10.5 配置项与默认值

```yaml
sipproxy:
  enabled: true                                    # 主开关
  instance-id: node1                               # 集群广播实例标识
  sip:
    port: 5561                                     # SIP UDP/TCP 监听端口
    bind-address: 0.0.0.0                          # 绑定地址
    public-ip:                                     # 公网 IP（Contact/Via 改写）
    public-port: 5561                              # 公网端口
  websocket:
    enabled: true                                  # WS 模块开关
    path: /sipproxy/ws                             # WS 端点路径
    require-auth: true                             # 强制握手认证
    auth-token-source: query                       # token 提取方式
    token-query-param: token                       # URL 参数名
  heartbeat:
    options-enabled: true                          # OPTIONS 心跳响应
    options-allow-methods: "INVITE, ACK, ..."      # Allow 头方法集
    idle-timeout: 90                               # 心跳超时（秒）
    zombie-clean-enabled: true                     # 僵尸清理
  cluster:
    sender-type: local                             # local|redis|rocketmq|rabbitmq|kafka
    sender-redis-channel: ipcc:sipproxy:ws:broadcast
    sender-rocketmq-topic:                         # RocketMQ topic
    sender-kafka-topic:                            # Kafka topic
    sender-rabbitmq-exchange:                      # RabbitMQ exchange
  session:
    redis-key-prefix: ipcc:sipproxy:session:
    session-ttl: 120                               # 会话 TTL（秒）
    register-ttl: 3600                             # 注册 TTL（秒）
```

***

## 十一、关键设计决策分析

### 11.1 B2BUA 头域改写

依据方案文档 2.1 节 B2BUA 角色定位，sipproxy 需要在两段对话中独立改写头域，保证：

1. **Contact/Via 替换为 SIP 代理地址**：后续信令能正确路由回 sipproxy
2. **Request-URI 修改为目标节点地址**：消息能送达目标 FS/第三方网关
3. **Record-Route 移除**：B2BUA 天然位于两段对话中间，无需 Record-Route 留在信令路径

#### 11.1.1 标准头域改写 `modifyHeadersForForwarding`

| 头域          | Request 处理                                                            | Response 处理                                                             |
| ----------- | --------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| Contact     | 替换为 `sipProxyPublicIp:publicPort;transport=xxx`                       | 同 Request                                                               |
| Via         | 重建为 `sipProxyPublicIp:publicPort;transport=xxx;branch=branchId;rport` | 重建为 `targetNode.ip:port;transport=xxx;branch=branchId;rport`（响应回送到来源节点） |
| Request-URI | 修改为 `targetNode.ip:targetNode.port`                                   | -                                                                       |

#### 11.1.2 WebSocket 头域改写 `modifyWsProxyHeaders`

| 头域          | 处理                                                                                                            |
| ----------- | ------------------------------------------------------------------------------------------------------------- |
| Contact     | 替换为 `localIpAddress:sipPort;transport=ws`                                                                     |
| Via         | 重建为 `localIpAddress:sipPort;transport=ws;branch=branchId;rport`                                               |
| Request-URI | 优先使用 `sessionInfo.websocketContactXxx`（来自注册或首次 INVITE 缓存），无则用 `localIpAddress:sipPort;transport=ws` + 原始 user |
| SDP         | `modifySdpForWebSocket` 替换 `c=IN IP4` 为 FS 公网 IP（WebRTC SDP 改写）                                                |

#### 11.1.3 出局信令改写 `OutboundGatewayRewriter.rewrite`

依据方案文档 6.2.2 节，向第三方网关出局时执行改写（默认实现 3 步，父程序可自定义）：

| 步骤 | 头域                  | 改写规则                                                                                  |
| -- | ------------------- | ------------------------------------------------------------------------------------- |
| 1  | From                | `callerIdInFrom=0` → 原始主叫号码；`callerIdInFrom=1`（默认）→ `gateway.externalLineNumber`（DID） |
| 2  | P-Asserted-Identity | 注入 `<sip:originalCaller@fromDomain>`（运营商鉴权用）                                          |
| 3  | Record-Route        | 移除（拓扑隐藏，Via 头已在 modifyHeadersForForwarding 中替换）                                       |

### 11.2 网关 ID 覆盖优先级

依据方案文档 3.3 节「网关 ID 覆盖优先级」，三层覆盖逻辑：

| 优先级  | 来源                                            | 代码体现                                                                                                                                                     |
| ---- | --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1（高） | `CallInfo.gatewayId`（INVITE 头 `X-Gateway-Id`） | `WsInviteRequestHandler.extractGatewayId()` 与 `SipInviteRequestHandler.extractGatewayId()` 提取后存入 `SessionInfo.gatewayId`，再由 ESL 层存入 `CallInfo.gatewayId` |
| 2（中） | `IVR 转接节点 routeValue`（routeType=2 时）          | 由 IVR 流程配置提供，sipproxy 模块不参与                                                                                         |
| 3（低） | 当前连接的 FS（CHANNEL\_PARK 事件来源 FS）               | 由 ESL 层 `FsCallIRouteProcess.handler` 的 `address` 参数提供，sipproxy 模块不参与                                                                                    |

**sipproxy 模块的职责**：

- 仅负责提取 `X-Gateway-Id` 头并存入 `SessionInfo.gatewayId`
- 透传 `X-Gateway-Id` 头到 FS，FS 自动转为 `sip_h_X-Gateway-Id` 通道变量
- ESL 层从事件 `variable_sip_h_X-Gateway-Id` 读取后存入 `CallInfo.gatewayId`
- 实际的网关 ID 覆盖决策由 IVR 转接节点实现（在 sipproxy 模块外）

### 11.3 豁免场景识别

依据方案文档 3.5 节「三个豁免场景」，sipproxy 在 `SipInviteRequestHandler.handle()` 中识别"FS 源 + 携带 X-Gateway-Id"组合：

```java
// 1. callType 标记阶段
if (SipProxyConstants.FREESWITCH.equals(source) && StrUtil.isNotBlank(gatewayId)) {
    callType = SipProxyConstants.CALL_TYPE_OUTBOUND;
} else if (SipProxyConstants.FREESWITCH.equals(source)) {
    callType = SipProxyConstants.CALL_TYPE_INTERNAL;
} else if (SipProxyConstants.THIRD_PARTY.equals(source)) {
    callType = SipProxyConstants.CALL_TYPE_INBOUND;
} else {
    callType = SipProxyConstants.CALL_TYPE_INTERNAL;
}

// 2. 豁免分支
if (SipProxyConstants.FREESWITCH.equals(source) && StrUtil.isNotBlank(gatewayId)) {
    messageForwarder.forwardToOutboundGateway(request, gatewayId);
    return;
}

// 3. 快速推 WebSocket 分支（被叫是已注册 JsSIP 坐席）
if (SipProxyConstants.FREESWITCH.equals(source) && StrUtil.isBlank(gatewayId)) {
    AgentInfo agent = agentInfoProvider.getAgent(toUser, null);
    if (agent != null) {
        // 按 Via 端口覆盖 freeSwitchNode，避免 FS originate→sipproxy→FS park 死循环
        FsNodeInfo sourceFsNode = nodeManager.selectFreeSwitchNodeByViaPort(callId, viaPort);
        if (sourceFsNode != null) {
            sessionInfo.setFreeSwitchNode(sourceFsNode);
            sessionManager.cacheSessionInfo(sessionInfo);
        }
        messageForwarder.forwardToWebSocketByUser(toUser, agentDomain, request);
        return;
    }
}

// 4. 默认分支
messageForwarder.forwardToFreeSwitch(request, freeSwitchNode);
```

### 11.4 ESL 全权控制

依据方案文档第二章「FreeSWITCH 仅作为'哑'媒体服务器」与第十四章「ESL 全权控制能力」的设计，sipproxy 不做呼叫决策，全部 park 到 FS 由 ESL 控制。

**代码体现**：

1. **sipproxy 不做呼叫决策**：
   - `WsInviteRequestHandler.doHandle` 不区分内部呼叫/外呼，统一 `callType=OUTBOUND` 转发到 FS park
   - `SipInviteRequestHandler.handle` 默认分支（非豁免、非快速推 WS）统一 `forwardToFreeSwitch` 转发到 FS park
   - 号码路由匹配、IVR 流程驱动、ESL originate、bridgeCall 等呼叫决策全部在 ESL 层（sipproxy 模块外）完成
2. **sipproxy 仅做信令转发**：
   - 所有 INVITE 转发到 FS park → FS 触发 `CHANNEL_PARK` 事件 → ESL 处理器接管
   - BYE/PRACK/UPDATE/INFO 等会话内方法按 source × callType 决策转发目标
   - 响应按 `ResponseForwardingStrategy` 策略表转发
3. **sipproxy 不参与媒体处理**：
   - 不处理 RTP/SDP 媒体协商（由 FS 完成，`SdpProcessor` 默认透传）
   - 不参与 ICE 协商（方案文档 17.4 节"SIP 代理不参与 ICE 协商，但需确保 SDP 透传完整"）
   - 仅协调媒体路径决策（通过 ESL originate/uuid\_bridge 确保 FS 作为媒体锚点）
4. **sipproxy 不直连 FreeSWITCH ESL**：
   - REFER 转接等需 ESL 编排的场景通过 `SipMessageInterceptor` 扩展点委托父程序实现
   - 模块本身不依赖 `FsClient`/`FsCallCacheService` 等 ESL 客户端

**唯一例外**：豁免场景（场景四/五）下，sipproxy 直接 `forwardToOutboundGateway` 出局，跳过 FS park + 号码路由 + IVR，但仍不参与媒体处理（媒体仍经 FS 中继，由 ESL originate 锚定）。

### 11.5 SessionInfo 上下文来源校正

`UnifiedResponseHandler.correctSourceBySessionContext` 优先级高于 `MessageSourceIdentifier.identifySource`，解决以下误识别场景：

- **FS 型 SBC 误识别**：存在 `thirdPartyNode` 或 `gatewayId` → 强制 source=THIRD_PARTY（即使 Via 来源 IP 命中 FS 节点列表）
- **Via received 是 sipproxy 自身 IP 的误识别**：仅存在 `freeSwitchNode` + 初始识别为 WEBSOCKET → 校正为 FREESWITCH（响应刚发给谁就来自谁）

***

## 十二、与方案文档的一致性分析

### 12.1 已实现的设计

| 方案文档要求                                                     | 代码现状                                                                  | 状态  |
| ---------------------------------------------------------- | --------------------------------------------------------------------- | --- |
| 6.3 第三方网关返回 407 Proxy Auth 时重新注入 Authorization 头并重发 INVITE | `GatewayAuthManager.handle407Challenge` 已实现：从 407 响应提取 realm/nonce/qop/algorithm/stale，从 `GatewayInfo` 获取凭证，按 RFC 2617 计算 Digest（支持无 qop 和 qop=auth 两种模式），注入 `Proxy-Authorization` 重发 INVITE，`authChallengeCount` + `last407Nonce` 防循环（MAX=2） | 已实现 |
| 14.6 Timer B 按网关 ID 维度可配置                                  | `GatewayInfo.retrySeconds` 等字段已定义，`forwardToOutboundGateway` 转发前记录配置日志。**限制**：JAIN SIP 不支持运行时按事务动态调整 Timer B，实际值由 SipStack 全局配置决定 | 已实现（含限制） |
| 17.4 ICE 协商中 SDP 透传完整性保证                                   | `SipMessageForwarder.validateIceCandidateInSdp` 已实现：`modifyHeadersForForwarding` 末尾校验 SDP，包含 `a=ice-ufrag` 但无 `a=candidate` 时记录 warn 日志 | 已实现 |
| 17.2 M3 PRACK/UPDATE 专门处理 | `SipDefaultRequestHandler` / `WsDefaultRequestHandler` 已改造为按 Call-ID 查 `SessionInfo` + 复用 `ResponseForwardingStrategy` 决策转发，`modifyHeadersForForwarding` 仅改写 Contact/Via/Request-URI，RSeq/RAck/Require 等 100rel 头域原样透传 | 已修复 |

> **已移除至未来演进**：
> - **4.2 FS 故障切换已 bridge 通话恢复**：复杂度极高，依赖媒体路径切换和 ESL originate 重建呼叫腿，FS 自身 RTP 超时检测可兜底，详见方案文档 15.3。
> - **17.1 Session Timer B2BUA 两侧维护**：复杂度极高，需双段独立维护 re-INVITE 调度，FS 已有 `session-timeout-sec` 配置兜底，详见方案文档 15.3。

<br />

***

## 十三、附录

### 附录 A：sipproxy 文件清单

| 文件路径（相对 cn/ipcc/sipproxy/）                                    | 职责                                                                                            |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `core/SipProxyService.java`                                | 入口服务，初始化 SIP 栈，分发请求/响应到对应工厂                                                                   |
| `core/annotation/SipMethod.java`                           | SIP 方法注解，工厂自动扫描注册                                                                             |
| `core/auth/GatewayAuthManager.java`                        | 网关 Digest 鉴权管理，处理 407 Proxy Authentication Required                                          |
| `core/forwarder/SipMessageForwarder.java`                  | 核心转发器，封装 FS/第三方/WS/出局网关四种转发目标，含头域改写与故障转移                                                       |
| `core/node/SipNodeManager.java`                            | FS 节点选择（一致性哈希）、ViaPort 节点匹配、第三方节点按来源 IP 反查、备用节点选择                                              |
| `core/session/SessionInfo.java`                            | 会话信息数据载体                                                                                      |
| `core/session/SipSessionManager.java`                      | 会话信息与注册信息的 Redis 读写                                                                           |
| `core/utils/SipAnalysisUtil.java`                          | SIP 文本/对象解析、头域提取、来源 IP 提取、响应构造等工具方法                                                           |
| `core/handler/AbstractSipHandler.java`                     | 所有 SIP 处理器公共基类，提供注册用户校验、坐席在线检测能力                                                              |
| `core/handler/request/sip/AbstractSipRequestHandler.java`  | SIP 来源（FS/第三方）请求处理器基类，定义 handle 抽象方法与按注册转发兜底                                                  |
| `core/handler/request/sip/SipRequestHandlerFactory.java`   | SIP 请求处理器工厂，@SipMethod 注解自动注册                                                                 |
| `core/handler/request/sip/SipInviteRequestHandler.java`    | SIP 来源 INVITE 处理器，识别豁免场景直接出局、识别已注册坐席直接推 WebSocket                                              |
| `core/handler/request/sip/SipByeRequestHandler.java`       | SIP 来源 BYE 处理器，按 To 头注册状态转发到坐席/第三方                                                            |
| `core/handler/request/sip/SipDefaultRequestHandler.java`   | SIP 来源默认处理器（PRACK/UPDATE/INFO 等会话内方法），按 Call-ID 查 SessionInfo + ResponseForwardingStrategy 决策 |
| `core/handler/request/ws/AbstractWsSipRequestHandler.java` | WS 来源请求处理器基类，模板方法 handle 校验 Call-ID 后调 doHandle                                               |
| `core/handler/request/ws/WsSipRequestHandlerFactory.java`  | WS 请求处理器工厂，@SipMethod 注解自动注册                                                                  |
| `core/handler/request/ws/WsInviteRequestHandler.java`      | 坐席 INVITE 处理器，re-INVITE 检测、统一转发到 FS park，提取 X-Gateway-Id                                      |
| `core/handler/request/ws/WsByeRequestHandler.java`         | 坐席 BYE 处理器，转发到会话绑定的 FS 节点                                                                     |
| `core/handler/request/ws/WsReferRequestHandler.java`       | 坐席 REFER 转接处理器，通过 SipMessageInterceptor 扩展点委托父程序实现 ESL 编排                                    |
| `core/handler/request/ws/WsRegisterRequestHandler.java`    | 坐席 REGISTER 处理器，Digest 鉴权 + 注册信息缓存（委托 SipAuthenticationProvider 扩展点）                          |
| `core/handler/request/ws/WsOptionsRequestHandler.java`     | 坐席 OPTIONS 心跳处理器，回 200 OK + Allow 头                                                           |
| `core/handler/request/ws/WsDefaultRequestHandler.java`     | 坐席默认处理器，会话内方法按 SessionInfo + ResponseForwardingStrategy 决策                                     |
| `core/handler/response/AbstractSipResponseHandler.java`    | 响应处理器基类，定义 determineResponseTarget + forwardResponse 模板方法，含 407 拦截逻辑                          |
| `core/handler/response/ResponseForwardingStrategy.java`    | 响应转发策略表 source × callType → target 三维映射                                                       |
| `core/handler/response/SipResponseHandlerFactory.java`     | 响应处理器工厂，统一返回 UnifiedResponseHandler                                                           |
| `core/handler/response/UnifiedResponseHandler.java`        | 统一响应处理器，按策略表决策并按 target 分支转发，含 SessionInfo 上下文来源校正                                              |
| `websocket/SipWebSocketHandler.java`                       | WebSocket 处理器，处理连接生命周期与 SIP 消息分发                                                              |
| `websocket/SipHandshakeInterceptor.java`                   | 握手拦截器，token 校验 + RFC 7118 SIP 子协议协商                                                            |
| `websocket/SipFrameReassembler.java`                       | SIP 消息分片重组，1MB 缓冲上限                                                                            |
| `websocket/LocalWsSessionManager.java`                     | 本地会话管理器默认实现                                                                                   |
| `websocket/WsSessionManager.java`                          | WebSocket 会话管理接口                                                                               |
| `websocket/ZombieSessionCleaner.java`                      | 僵尸会话定时清理器                                                                                      |
| `cluster/WsMessageSender.java`                             | 集群广播消息发送接口                                                                                    |
| `cluster/LocalWsMessageSender.java`                        | 单实例默认实现                                                                                       |
| `cluster/RedisWsMessageSender.java`                        | Redis pub/sub 实现                                                                              |
| `cluster/KafkaWsMessageSender.java`                        | Kafka 占位骨架                                                                                    |
| `cluster/RabbitMqWsMessageSender.java`                     | RabbitMQ 占位骨架                                                                                 |
| `cluster/RocketMqWsMessageSender.java`                     | RocketMQ 占位骨架                                                                                 |
| `cluster/SipWsBroadcastMessage.java`                       | 广播消息载体                                                                                        |
| `cluster/ClusterBroadcastConsumer.java`                    | 集群消费者，按 targetType 分发到本实例 WS 会话                                                                 |
| `api/agent/AgentInfoProvider.java`                         | 坐席信息查询扩展点                                                                                     |
| `api/authentication/AuthenticationCallback.java`           | 认证事件回调扩展点                                                                                     |
| `api/authentication/SipAuthenticationProvider.java`        | SIP Digest 认证扩展点                                                                               |
| `api/authentication/WsHandshakeAuthenticator.java`         | WS 握手认证扩展点                                                                                     |
| `api/fs/FsNodeProvider.java`                               | FS 节点查询扩展点                                                                                     |
| `api/gateway/GatewayProvider.java`                         | 网关查询扩展点                                                                                       |
| `api/gateway/MessageSourceIdentifier.java`                 | 消息来源识别扩展点                                                                                     |
| `api/gateway/OutboundGatewayRewriter.java`                 | 出局信令改写扩展点                                                                                     |
| `api/interceptor/SipMessageInterceptor.java`               | SIP 消息拦截扩展点（REFER ESL 编排委托）                                                                   |
| `api/media/SdpProcessor.java`                              | SDP 处理扩展点                                                                                      |
| `api/security/IpWhitelist.java`                            | IP 白名单扩展点                                                                                      |
| `api/security/SipRateLimiter.java`                         | SIP 限流扩展点                                                                                      |
| `api/trace/TraceContext.java`                              | 链路追踪扩展点                                                                                       |
| `api/transport/SipMessageTransport.java`                   | SIP 消息传输扩展点                                                                                    |
| `defaults/agent/DefaultAgentInfoProvider.java`             | 默认坐席信息查询（返回 null）                                                                             |
| `defaults/authentication/DefaultSipAuthenticationProvider.java` | 默认 SIP 认证（本地 Digest 校验）                                                                       |
| `defaults/authentication/DefaultWsHandshakeAuthenticator.java` | 默认 WS 握手认证（全部放行）                                                                              |
| `defaults/authentication/NoopAuthenticationCallback.java`  | 默认认证回调（日志记录）                                                                                  |
| `defaults/fs/DefaultFsNodeProvider.java`                   | 默认 FS 节点查询（空列表）                                                                               |
| `defaults/gateway/DefaultGatewayProvider.java`             | 默认网关查询（空列表）                                                                                   |
| `defaults/gateway/DefaultMessageSourceIdentifier.java`     | 默认消息来源识别（6 层递进）                                                                               |
| `defaults/gateway/DefaultOutboundGatewayRewriter.java`     | 默认出局信令改写（3 步）                                                                                 |
| `defaults/interceptor/NoopSipMessageInterceptor.java`      | 默认消息拦截器（不拦截）                                                                                  |
| `defaults/media/DefaultSdpProcessor.java`                  | 默认 SDP 处理器（透传）                                                                                |
| `defaults/security/DefaultIpWhitelist.java`                | 默认 IP 白名单（全部放行）                                                                               |
| `defaults/security/DefaultSipRateLimiter.java`             | 默认 SIP 限流（全部放行）                                                                               |
| `defaults/trace/DefaultTraceContext.java`                  | 默认链路追踪（ThreadLocal）                                                                           |
| `defaults/transport/DefaultSipMessageTransport.java`       | 默认 SIP 消息传输（空实现）                                                                              |
| `support/RedisConstants.java`                              | Redis Key 前缀与 TTL 常量                                                                          |
| `support/SipProxyConstants.java`                           | 信令来源、callType、JSSIP 标识、支持方法集合等常量                                                              |
| `support/SipProxyErrorCodeConstants.java`                  | 错误码常量（500-599 区间）                                                                             |
| `support/SipProxyException.java`                           | 模块统一运行时异常                                                                                     |
| `support/model/AgentInfo.java`                             | 坐席信息模型                                                                                        |
| `support/model/FsNodeInfo.java`                            | FreeSWITCH 节点信息模型                                                                             |
| `support/model/GatewayInfo.java`                           | 网关信息模型                                                                                        |
| `autoconfigure/SipProxyAutoConfiguration.java`             | 主自动配置，注册 13 个扩展点默认实现                                                                          |
| `autoconfigure/SipProxyWebSocketAutoConfiguration.java`    | WebSocket 自动配置                                                                                |
| `autoconfigure/SipProxyClusterAutoConfiguration.java`      | 集群广播自动配置                                                                                      |
| `autoconfigure/SipProxySessionAutoConfiguration.java`      | 会话存储自动配置（Redis 依赖声明）                                                                          |
| `autoconfigure/SipProxyProperties.java`                    | 配置属性类                                                                                         |

### 附录 B：关键 SIP 头域处理矩阵

| SIP 方法                    | Call-ID                      | From                             | To                                  | Via                                                               | Route/Record-Route                 | Contact                                                           | X-Gateway-Id                                       | 其他特殊头域                                                  |
| ------------------------- | ---------------------------- | -------------------------------- | ----------------------------------- | ----------------------------------------------------------------- | ---------------------------------- | ----------------------------------------------------------------- | -------------------------------------------------- | ------------------------------------------------------- |
| INVITE（WS 来源）             | 原样保留，用于 SessionInfo 索引       | 原样保留，提取 fromUser/fromDomain 用于日志 | 原样保留，提取 toUser/toDomain 校验          | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | 不处理（FS park 模式不需要）                 | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | extractGatewayId 提取后存入 SessionInfo                 | -                                                       |
| INVITE（FS 来源，豁免）          | 原样保留                         | OutboundGatewayRewriter 改写为 DID 或原始主叫 | 原样保留                                | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | OutboundGatewayRewriter 移除 Record-Route | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | extractGatewayId 提取后作为 forwardToOutboundGateway 参数 | OutboundGatewayRewriter 注入 P-Asserted-Identity |
| INVITE（FS 来源，默认）          | 原样保留                         | 原样保留                             | 原样保留                                | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | 不处理                                | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | extractGatewayId 提取后存入 SessionInfo                 | -                                                       |
| INVITE（第三方来源）             | 原样保留                         | 原样保留                             | 原样保留                                | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | 不处理                                | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | 一般不携带（如携带按 FS 来源逻辑处理）                              | -                                                       |
| BYE（WS 来源）                | 原样保留                         | 原样保留                             | 原样保留                                | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | 不处理                                | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | 不处理                                                | -                                                       |
| BYE（FS/第三方来源）             | 原样保留                         | 原样保留                             | 原样保留，提取 toUser/toDomain 用于按注册转发     | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | 不处理                                | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | 不处理                                                | -                                                       |
| REGISTER（WS 来源）           | 原样保留                         | 原样保留，提取 realm 用于鉴权               | 原样保留                                | 原样保留（直接 toWebSocket 发回坐席）                                         | 不处理                                | 200 OK 响应带回原始 Contact 头                                           | 不处理                                                | WWW-Authenticate（401 响应）、Authorization（鉴权请求）            |
| OPTIONS（WS 来源）            | 原样保留                         | 原样保留                             | 原样保留                                | 原样保留（直接 toWebSocket 发回坐席）                                         | 不处理                                | 不处理                                                               | 不处理                                                | Allow 头（200 OK 响应）                                      |
| REFER（WS 来源）              | 原样保留                         | 原样保留，提取 fromUser                | 原样保留                                | 原样保留（不转发到 FS/第三方，仅回 202 Accepted）                                 | 不处理                                | 不处理                                                               | extractHeader 提取作为 SipMessageInterceptor 参数        | Refer-To（提取目标号码）、X-Transfer-Type（转接类型）                  |
| PRACK/UPDATE/INFO（SIP 来源） | 原样保留，按 Call-ID 查 SessionInfo | 原样保留                             | 原样保留，提取 toUser/toDomain 用于 fallback | modifyHeadersForForwarding 或 modifyWsProxyHeaders 替换（按 target 决策） | 不处理                                | modifyHeadersForForwarding 或 modifyWsProxyHeaders 替换（按 target 决策） | 不处理                                                | Require/RSeq/RAck 等 100rel 头域原样透传（不做特殊处理）               |
| PRACK/UPDATE/INFO（WS 来源）  | 原样保留                         | 原样保留                             | 原样保留                                | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | 不处理                                | modifyHeadersForForwarding 替换为 sipProxyPublicIp                   | 不处理                                                | 原样透传（WsDefaultRequestHandler 复用 ResponseForwardingStrategy 决策） |
| 响应（任意来源）                  | 原样保留，按 Call-ID 查 SessionInfo | 原样保留                             | 原样保留                                | modifyHeadersForForwarding（→FS）或 modifyWsProxyHeaders（→WS）替换      | 不处理                                | modifyHeadersForForwarding 或 modifyWsProxyHeaders 替换              | 不处理                                                | 407 响应触发 GatewayAuthManager 重发 INVITE                  |

***

> **文档说明**：本文档基于 ipcc-sipproxy 模块代码（独立 Spring Boot 模块，包名 `cn.ipcc.sipproxy`）与《SIP通信系统信令与话务流程方案》v3.0 生成，所有分析点均有代码或文档依据。如代码或方案文档后续更新，本文档需同步更新。
