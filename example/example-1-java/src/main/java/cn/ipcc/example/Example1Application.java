package cn.ipcc.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * example-1-java 示例工程启动类。
 *
 * <p>项目用途：集成 ipcc-sipproxy 模块的默认实现 + H2 内存数据库 seed 数据，
 * 用于验证 SIP 注册等基础信令功能。</p>
 *
 * <p>设计说明：</p>
 * <ul>
 *     <li>不实现任何 ipcc-sipproxy 扩展点接口，全部扩展点（AgentInfoProvider、
 *         FsNodeProvider、GatewayProvider、WsHandshakeAuthenticator 等 13 个）
 *         均使用 sipproxy 通过 {@code @ConditionalOnMissingBean} 提供的默认实现。</li>
 *     <li>H2 内存数据库在启动时执行 classpath 下的 schema.sql 与 data.sql
 *         （来源于 ipcc-sipproxy 依赖 jar），为默认实现提供 seed 数据。</li>
 *     <li>会话与注册信息存储依赖 Redis，需确保本地 Redis 可用。</li>
 * </ul>
 *
 * @author ipcc
 */
@SpringBootApplication
public class Example1Application {

    public static void main(String[] args) {
        SpringApplication.run(Example1Application.class, args);
    }
}
