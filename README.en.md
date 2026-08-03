# ipcc-sipproxy

> A fully self-contained SIP proxy service (B2BUA) module built on Spring Boot 3 + JAIN-SIP. It provides SIP over WebSocket access, B2BUA signaling forwarding, session management, and cluster broadcast capabilities. Through 13 extension-point interfaces, it is completely decoupled from the host application and can be reused by any Spring Boot project.

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![JAIN-SIP](https://img.shields.io/badge/JAIN--SIP-1.2.1.4-blue.svg)](https://jain-sip.dev.java.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

📖 Language: [简体中文](./README.md) | [English](./README.en.md)

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Tech Stack](#tech-stack)
- [Architecture Overview](#architecture-overview)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Extension Points](#extension-points)
- [Example Projects](#example-projects)
- [Project Structure](#project-structure)
- [Development Constraints](#development-constraints)
- [Key Documentation](#key-documentation)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

`ipcc-sipproxy` is a **SIP proxy service (B2BUA, Back-to-Back User Agent)** designed for call-center / IPPBX scenarios. At the protocol level it is not a simple SIP Proxy — it maintains two independent dialogs as a back-to-back user agent and assumes the following five core responsibilities:

| Responsibility | Implementation | Description |
| --- | --- | --- |
| Agent registration | `WsRegisterRequestHandler` | Handles agent REGISTER requests; caches registration after Digest auth |
| Authentication | `SipAuthenticationProvider` extension point | Digest auth (HA1/HA2 + nonce comparison) |
| Request routing | `SipRequestHandlerFactory` / `WsSipRequestHandlerFactory` | Routes by SIP method; number routing is handled by the ESL layer |
| Protocol conversion | `SipProxyService` + `SipWebSocketHandler` | Listens on UDP + TCP simultaneously; WebSocket messages are reassembled and parsed into JAIN-SIP objects |
| Session management | `SipSessionManager` + `SessionInfo` | Maintains Call-ID → SessionInfo mapping; records FS node, third-party node, callType, gateway ID, etc. |

The module adopts a **fully independent POM** design (it does not inherit `spring-boot-starter-parent` and has no dependency on the yudao framework). It is decoupled from the host application through 13 extension-point interfaces, with default implementations that allow the module to start standalone.

## Key Features

- **B2BUA signaling core**: two independent INVITE dialogs, no reliance on Record-Route, BYE coordinated on both legs independently
- **Dual ingress**: UDP/TCP (JAIN-SIP) + WebSocket (RFC 7118 `sip` sub-protocol); compatible with JsSIP / Linphone / MicroSIP and other clients
- **WebSocket robustness**: frame reassembly (1 MB buffer cap), scheduled zombie-session cleanup, handshake token authentication
- **13 extension-point APIs**: agent/FS-node/gateway lookup, SDP processing, IP whitelist, SIP rate limiting, Digest auth, WS handshake auth, auth callbacks, REFER ESL orchestration delegation, tracing, custom transport
- **Cluster broadcast**: 5 `WsMessageSender` implementations (local / redis / kafka / rabbitmq / rocketmq), dispatched by USER / SESSION / ALL target types
- **Gateway Digest auth**: handles 407 Proxy Authentication Required; supports RFC 2617 standard Digest + qop=auth + stale re-challenge
- **Spring Boot auto-configuration**: 4 `AutoConfiguration` classes; `sipproxy.enabled=false` disables the module with a single switch
- **Session state persistence**: Redis-backed; SessionInfo TTL=120s, registration TTL=3600s, refreshed on in-session methods

## Tech Stack

| Category | Choice | Version |
| --- | --- | --- |
| Language | Java | 17 |
| Framework | Spring Boot | 3.5.9 (independent POM, versions managed via BOM) |
| SIP stack | JAIN-SIP (gov.nist RI) | 1.2.1.4 (unified version to avoid cross-version AbstractMethodError) |
| Cache | Redis (StringRedisTemplate) | — |
| Cluster broadcast | Redis pub/sub / Kafka / RabbitMQ / RocketMQ | optional, default local |
| Utilities | hutool / lombok / log4j-over-slf4j | 5.8.27 / 1.18.34 / 2.0.16 |

## Architecture Overview

The sipproxy module follows a five-layer architecture — *Entry → Factory → Handler → Forwarder → Node/Session management* — augmented by a WebSocket ingress layer, a cluster-broadcast layer, and an extension-point API layer:

```text
┌──────────────────────────────────────────────────────────────────────────┐
│  Entry layer: SipProxyService (implements SipListener)                    │
│  ├── processRequest(): UDP/TCP SIP request entry                          │
│  ├── processResponse(): UDP/TCP SIP response entry                        │
│  └── handleWebSocketSipMessage(): WebSocket SIP message entry             │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
        ┌──────────────────────┴──────────────────────┐
        │                                             │
┌───────▼────────────────┐                ┌──────────▼──────────────┐
│ Factory (method route)  │                │ Factory (response unify) │
│ SipRequestHandlerFactory│                │ SipResponseHandlerFactory│
│ WsSipRequestHandlerFactory│               │ → UnifiedResponseHandler │
│ (@SipMethod scan)       │                │                          │
└───────┬────────────────┘                └──────────┬──────────────┘
        │                                             │
┌───────▼───────────────────────────────────────────▼──────────────────┐
│  Handler layer: AbstractSipHandler                                     │
│  ├── handler/request/sip/ (SIP source: FS/third-party)                │
│  │   ├── SipInviteRequestHandler (@SipMethod INVITE)                  │
│  │   ├── SipByeRequestHandler (@SipMethod BYE)                        │
│  │   └── SipDefaultRequestHandler (PRACK/UPDATE/INFO in-session)      │
│  └── handler/request/ws/  (WS source: JsSIP agents)                   │
│      ├── WsInviteRequestHandler / WsByeRequestHandler                 │
│      ├── WsReferRequestHandler (@SipMethod REFER)                     │
│      ├── WsRegisterRequestHandler (@SipMethod REGISTER)               │
│      └── WsOptionsRequestHandler (@SipMethod OPTIONS)                 │
└──────────────────────────────┬────────────────────────────────────────┘
                               │
┌──────────────────────────────▼────────────────────────────────────────┐
│  Forwarder layer: SipMessageForwarder                                  │
│  ├── forwardToFreeSwitch (with failover + header rewrite)              │
│  ├── forwardToThirdParty / forwardToWebSocket                          │
│  ├── forwardToOutboundGateway (egress rewrite for exempt scenarios)    │
│  ├── modifyHeadersForForwarding (standard header rewrite)              │
│  ├── handle407ProxyAuth (delegated to GatewayAuthManager)              │
│  └── identifyMessageSource (delegated to MessageSourceIdentifier)      │
└──────────────────────────────┬────────────────────────────────────────┘
                               │
        ┌──────────────────────┴──────────────────────┐
        │                                             │
┌───────▼────────────────┐                ┌──────────▼──────────────┐
│ Node management         │                │ Session management       │
│ SipNodeManager          │                │ SipSessionManager        │
│ ├── FS node select/cache│                │ ├── SessionInfo cache    │
│ ├── Third-party select  │                │ ├── Registration cache   │
│ └── ViaPort match       │                │ └── Registration cleanup │
└─────────────────────────┘                └─────────────────────────┘
```

For the full architecture diagram (including the WebSocket ingress layer, cluster-broadcast layer, and extension-point API layer), see [sipproxy代码分析.md](./sipproxy代码分析.md).

## Quick Start

### Prerequisites

- JDK 17+
- Maven 3.8+
- Redis 5.x+ (for session and registration storage)

### 1. Build

```bash
git clone <repository-url>
cd ipcc-sipproxy
mvn clean package -DskipTests
# Output: target/ipcc-sipproxy.jar
```

### 2. Integrate into a Spring Boot project

Add the dependency to your project's `pom.xml`:

```xml
<dependency>
    <groupId>cn.ipcc</groupId>
    <artifactId>ipcc-sipproxy</artifactId>
    <version>1.0.0</version>
</dependency>
```

Add the minimal configuration to `application.yml`:

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

Start your Spring Boot application — sipproxy is auto-configured via `AutoConfiguration.imports`; no extra annotations are required.

### 3. Run the example projects

The repository ships with three ready-to-use examples under `example/`. See [Example Projects](#example-projects) for details.

## Configuration

All configuration keys use the `sipproxy.*` prefix and are bound by `SipProxyProperties`:

| Key | Default | Description |
| --- | --- | --- |
| `sipproxy.enabled` | `true` | Master switch; when `false`, auto-configuration is skipped |
| `sipproxy.instance-id` | `node1` | Cluster instance identifier; must be unique per node (recommend `${HOSTNAME:node1}`) |
| `sipproxy.sip.port` | `5561` | SIP UDP/TCP listen port |
| `sipproxy.sip.public-ip` | — | Public IP used to rewrite Contact/Via headers |
| `sipproxy.websocket.path` | `/sipproxy/ws` | SIP over WebSocket ingress path |
| `sipproxy.websocket.require-auth` | `true` | Whether to invoke `WsHandshakeAuthenticator` for token validation |
| `sipproxy.heartbeat.idle-timeout` | `90` | WS idle timeout in seconds; sessions exceeding it are flagged as zombies |
| `sipproxy.heartbeat.zombie-clean-enabled` | `true` | Enable scheduled cleanup of zombie sessions |
| `sipproxy.cluster.sender-type` | `local` | Broadcast type: `local` / `redis` / `rocketmq` / `rabbitmq` / `kafka` |
| `sipproxy.session.session-ttl` | `120` | Session TTL in seconds; refreshed on in-session methods |
| `sipproxy.session.register-ttl` | `3600` | Registration TTL in seconds; refreshed on REGISTER renewal |

## Extension Points

sipproxy is decoupled from the host application through 13 extension-point interfaces under the `api/` package. Implement an interface and register it as a Spring Bean to override the default implementation provided under `defaults/` via `@ConditionalOnMissingBean`:

| Extension point | Purpose |
| --- | --- |
| `AgentInfoProvider` | Agent info lookup (replaces SysAgentService) |
| `FsNodeProvider` | Online FS node list |
| `GatewayProvider` | Gateway lookup |
| `MessageSourceIdentifier` | Message source identification (6-layer cascade) |
| `OutboundGatewayRewriter` | Outbound INVITE header rewrite |
| `SdpProcessor` | SDP media negotiation |
| `IpWhitelist` / `SipRateLimiter` | IP whitelist / SIP rate limiting |
| `SipAuthenticationProvider` | SIP Digest authentication |
| `WsHandshakeAuthenticator` | WS handshake token authentication |
| `AuthenticationCallback` | Authentication event callback |
| `SipMessageInterceptor` | **REFER ESL orchestration delegation** (key decoupling point) |
| `TraceContext` / `SipMessageTransport` | Tracing / custom transport |

**Extension example**:

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
        // Standard RFC 2617 Digest: HA1 = MD5(user:realm:pass), HA2 = MD5(method:uri)
        String ha1 = DigestUtil.md5Hex(extension + ":" + domain + ":" + agent.getPassword());
        String ha2 = DigestUtil.md5Hex(method + ":" + uri);
        String expected = DigestUtil.md5Hex(ha1 + ":" + nonce + ":" + ha2);
        return expected.equals(response);
    }
}
```

## Example Projects

The `example/` directory contains three complementary examples covering both integration modes — "default implementation" and "full extension-point override":

### example-1-java — Default implementation + H2

- **Role**: Minimal integration example; implements no extension points and relies entirely on sipproxy's `@ConditionalOnMissingBean` defaults
- **Datasource**: H2 in-memory DB; on startup it runs `classpath:schema.sql` + `data.sql` (shipped in the sipproxy jar). Seed data: agent `1001/123456`, FS node `127.0.0.1:5060`, gateway `127.0.0.1:5080`
- **Ports**: HTTP `8081` / SIP `5561`
- **Run**:

  ```bash
  cd example/example-1-java
  mvn spring-boot:run
  ```

### example-2-java — All 13 extension points overridden

- **Role**: Extension-point override example; demonstrates how the host application takes over all 13 extension points
- **Datasource**: No database; all data is hard-coded in `cn.ipcc.example.ext.*` implementations
- **Ports**: HTTP `8082` / SIP `5562`
- **Run**:

  ```bash
  cd example/example-2-java
  mvn spring-boot:run
  ```

### example-jssip — Vue3 + JsSIP frontend test page

- **Role**: A SIP soft-phone test page built with Vue 3.5 + Element Plus 2.x + JsSIP 3.10 for verifying SIP registration and basic call flows
- **Features**: Left-side config + soft-phone; right-side vertical split (operation logs 40% / WS messages 60%); live SIP signaling inspection
- **Run**:

  ```bash
  cd example/example-jssip
  npm install
  npm run dev    # http://localhost:5173
  ```

### test — Playwright automated tests

- **Role**: End-to-end registration tests built on Playwright; runs 5 rounds against `example-1-java` and `example-2-java` each (10 rounds total)
- **Assertions**: Agent registers successfully (status switches to online) + clicking the call button shows "测试示例，仅支持注册测试"
- **Run**:

  ```bash
  cd example/test
  pip install -r requirements.txt
  playwright install chromium
  python test_registration.py
  ```

## Project Structure

```text
ipcc-sipproxy/
├── src/main/java/cn/ipcc/sipproxy/
│   ├── api/              # 13 extension-point interfaces (decoupled from host)
│   ├── autoconfigure/    # 4 Spring Boot auto-configuration classes
│   ├── cluster/          # Cluster broadcast (5 WsMessageSender implementations)
│   ├── core/             # Core service, handlers, forwarder, node/session/auth mgmt
│   │   ├── SipProxyService.java       # Entry service (SipListener)
│   │   ├── annotation/SipMethod.java  # Handler auto-registration annotation
│   │   ├── auth/GatewayAuthManager.java    # 407 auth management
│   │   ├── forwarder/SipMessageForwarder.java  # Core forwarder
│   │   ├── handler/        # Request/response handlers (@SipMethod scan)
│   │   │   ├── request/sip/   # SIP-source (FS/third-party) request handlers
│   │   │   ├── request/ws/    # WS-source (JsSIP agent) request handlers
│   │   │   └── response/      # Response handlers
│   │   ├── node/SipNodeManager.java    # FS/third-party node selection
│   │   ├── session/        # SessionInfo + SipSessionManager
│   │   └── utils/SipAnalysisUtil.java  # SIP parsing utilities
│   ├── defaults/         # 13 default extension-point implementations (@ConditionalOnMissingBean)
│   ├── support/          # Constants, exceptions, models (AgentInfo/FsNodeInfo/GatewayInfo)
│   └── websocket/        # WebSocket ingress (handshake/reassembly/zombie cleanup)
├── src/main/resources/
│   ├── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   ├── schema.sql        # H2 seed schema (agent/FS node/gateway)
│   └── data.sql          # H2 seed data
├── example/              # Example projects
│   ├── example-1-java/   # Default implementation + H2
│   ├── example-2-java/   # All 13 extension points overridden
│   ├── example-jssip/    # Vue3 + JsSIP frontend test page
│   └── test/             # Playwright automated tests
├── pom.xml               # Independent POM (no parent inheritance)
├── sipproxy代码分析.md    # Full architecture & code analysis (required reading)
├── AGENTS.md             # Agent working context
├── README.md             # Chinese documentation
└── README.en.md          # This file
```

## Development Constraints

To preserve module independence and B2BUA semantics, contributors must follow these constraints:

- **No direct FreeSWITCH ESL connection**: Scenarios requiring ESL orchestration (e.g. REFER transfer) are delegated to the host application via the `SipMessageInterceptor` extension point. sipproxy itself does not pull in any ESL client dependency.
- **No call-routing decisions**: All INVITEs are parked to FS uniformly; number routing / IVR / bridge is handled by the ESL layer. sipproxy only forwards signaling.
- **B2BUA header rewrite**: `modifyHeadersForForwarding` replaces Contact/Via with the sipproxy public address and removes Record-Route — it relies on B2BUA center forwarding, not Record-Route, to stay on the signaling path.
- **Session state relies on Redis**: SessionInfo TTL=120s, registration TTL=3600s, refreshed on in-session methods. No in-memory state.
- **Unified JAIN-SIP version**: Must use 1.2.1.4 to avoid cross-version `AbstractMethodError`.
- **Adding new handlers**: Create a class under `core/handler/request/{sip,ws}/` and annotate it with `@SipMethod("XXX")`; the factory scans and registers it automatically — no manual routing table edits.

## Key Documentation

- **[sipproxy代码分析.md](./sipproxy代码分析.md)**: Full architecture and code analysis (required reading). Covers the B2BUA five-layer architecture and component inventory, request/response processing flows (SIP and WebSocket dual ingress), SIP method handling details (INVITE/BYE/REFER/REGISTER/OPTIONS/PRACK, etc.), session and state management (SessionInfo lifecycle, Redis key structure), extension-point API details (13 interfaces + default implementations), cluster broadcast, auto-configuration, key design decisions, and the SIP header processing matrix.
- **[AGENTS.md](./AGENTS.md)**: Agent working context — repository purpose, tech stack, build commands, project structure, and development constraints.
- **[README.md](./README.md)**: Chinese documentation.

## Contributing

1. Fork this repository
2. Create a `feat/xxx` or `fix/xxx` branch
3. Commit your changes (follow [Conventional Commits](https://www.conventionalcommits.org/))
4. Open a Pull Request describing the purpose of the change and how it was tested

Before submitting, please ensure:

- `mvn clean package` compiles successfully
- If you modify an extension-point interface, update the corresponding default implementation under `defaults/` and `sipproxy代码分析.md`
- If you modify configuration keys, update `SipProxyProperties` and the [Configuration](#configuration) table in this README

## License

[MIT License](./LICENSE)
