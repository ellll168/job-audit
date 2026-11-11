package com.example.jobaudit.config;

import com.github.xiaoymin.knife4j.spring.configuration.Knife4jProperties;
import com.github.xiaoymin.knife4j.spring.extension.Knife4jOpenApiCustomizer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 文档配置。
 * 默认关闭（application.properties 中 knife4j.enable=false），需要文档时用启动参数开启：
 * --springdoc.api-docs.enabled=true --knife4j.enable=true
 * 开启后访问 http://localhost:8080/doc.html 查看全部接口。
 */
@Configuration
@ConditionalOnProperty(name = "knife4j.enable", havingValue = "true")
public class Knife4jConfig {

    @Bean
    public OpenAPI jobAuditOpenAPI() {
        return new OpenAPI().info(
                new Info()
                        .title("岗位反向尽调助手 API")
                        .description("规则引擎 + LLM 双路信号提取，输出岗位风险审计结果")
                        .version("v1.0.0"));
    }

    @Bean
    public GroupedOpenApi jobAuditApi() {
        return GroupedOpenApi.builder()
                .group("job-audit")
                .displayName("业务接口")
                .pathsToMatch("/api/**")
                .build();
    }

    /**
     * 覆盖 knife4j 自带的同名 Bean（原方法标了 @ConditionalOnMissingBean）。
     * 原因：knife4j 4.5.0 的 addOrderExtension() 按 springdoc 2.3 编译，
     * 调用 SpringDocConfigProperties#getGroupConfigs() 取 List；Boot 3.5 需 springdoc 2.8，
     * 该方法返回 Set，运行时抛 NoSuchMethodError 导致 /v3/api-docs 500。
     * 该扩展只负责 UI 里的分组排序，跳过不影响文档展示。
     */
    @Bean
    public Knife4jOpenApiCustomizer knife4jOpenApiCustomizer(
            Knife4jProperties properties, SpringDocConfigProperties springDocConfigProperties) {
        return new Knife4jOpenApiCustomizer(properties, springDocConfigProperties) {
            @Override
            public void customise(OpenAPI openApi) {
                // 空实现：跳过与 springdoc 2.8 不兼容的排序扩展
            }
        };
    }
}
