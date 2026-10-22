package com.tasknest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：仅放行本机开发前端的跨域请求。
 *
 * <p>CORS 作用于 {@code /api/**}，允许 {@code localhost} 与 {@code 127.0.0.1} 的任意端口
 * （Vite 端口被占用时会顺延），方法限定为 GET / POST / PUT / OPTIONS，请求头仅允许
 * {@code Content-Type}，且不携带凭据。生产部署关闭跨域时应移除此配置。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 注册本机开发源的 CORS 规则，预检结果缓存 3600 秒。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 只放行本机开发源；Vite 端口被占用时会自动顺延，因此按本机任意端口匹配。
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
