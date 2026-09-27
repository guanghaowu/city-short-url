package com.cityshorturl.config;

import com.cityshorturl.common.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册 JWT 拦截器。
 *
 * <p>拦截策略是「<b>默认拦截 /api/**，白名单放行</b>」——
 * 比「默认放行、逐个加拦截」安全得多：新增接口时忘了加拦截会变成越权漏洞，
 * 而忘了加白名单只会导致接口暂时访问不了，是可发现的错误。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/health",
                        "/api/user/register",
                        "/api/user/login",
                        // 接口文档相关
                        "/doc.html",
                        "/webjars/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                );
        // 注：短链跳转是 GET /{shortCode}，不在 /api/** 下，天然不拦截。
    }
}
