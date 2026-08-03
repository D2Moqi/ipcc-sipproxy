package cn.ipcc.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * ipcc-sipproxy 集成示例工程启动类（Java 版）。
 * <p>
 * 设计意图：演示标准 Spring Boot 工程集成 ipcc-sipproxy 模块的最小入口，
 * 通过 {@code @Component} 扫描 {@code cn.ipcc.example.ext} 包下的 14 个扩展点实现类，
 * 覆盖 sipproxy 的 {@code @ConditionalOnMissingBean} 默认实现。
 * <p>
 * 排除 {@link DataSourceAutoConfiguration}：本示例不使用数据库，所有数据硬编码在扩展点实现类中，
 * 避免自动配置因缺少 DataSource 报错。
 *
 * @author ipcc
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class Example2Application {

    public static void main(String[] args) {
        SpringApplication.run(Example2Application.class, args);
    }
}
