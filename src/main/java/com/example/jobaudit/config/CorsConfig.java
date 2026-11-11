package com.example.jobaudit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 全局跨域配置：只允许本地前端开发服务器访问 /api/**。
 * 已关闭 allowCredentials：本项目不依赖 Cookie 鉴权，开放凭证只会放大 CSRF 风险。
 * 部署到正式域名时，把 FRONTEND_ORIGINS 改成实际站点地址。
 */
@Configuration
public class CorsConfig {

    /**
     * 前端 Vite 开发服务器地址。
     * 5173 被占用时 Vite 会自动顺延到 5174，因此两个端口都要放行，避免演示现场接口全挂。
     */
    private static final List<String> FRONTEND_ORIGINS = List.of(
            "http://localhost:5173",
            "http://localhost:5174");

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        FRONTEND_ORIGINS.forEach(config::addAllowedOrigin);
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
