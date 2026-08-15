package com.wisesoft.ai.config;

import com.wisesoft.ai.dto.ResultJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部鉴权拦截器
 * 只允许携带正确 X-Trusted-Token 的请求（来自平台网关代理）
 * token 必须通过环境变量 AI_TRUSTED_TOKEN 配置（缺失则启动失败），
 * 比较采用恒定时间算法防止时序攻击
 *
 * @author yuanke
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

    private final AiAppProperties properties;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void validate() {
        if (properties.getTrustedToken() == null || properties.getTrustedToken().isBlank()) {
            throw new IllegalStateException(
                    "缺少必要配置：AI_TRUSTED_TOKEN 环境变量未设置，服务拒绝启动");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TrustedTokenInterceptor())
                .addPathPatterns("/api/**");
    }

    class TrustedTokenInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String token = request.getHeader("X-Trusted-Token");
            String expected = properties.getTrustedToken();
            // 恒定时间比较，避免时序攻击
            boolean ok = token != null
                    && MessageDigest.isEqual(
                            token.getBytes(StandardCharsets.UTF_8),
                            expected.getBytes(StandardCharsets.UTF_8));
            if (!ok) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ResultJson.error("无权访问 AI 服务")));
                return false;
            }
            return true;
        }
    }
}
