package com.wisesoft.ai.config;

import com.wisesoft.ai.service.ImageUrlSigner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 图片静态资源鉴权拦截器
 * 生产开启图片鉴权（AI_IMAGES_AUTH_ENABLED=true）时，校验请求中的 expire + sig 签名；
 * 未开启时直接放行（本地开发）
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class ImageAuthInterceptor implements HandlerInterceptor {

    private final ImageUrlSigner signer;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!signer.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        String expireStr = request.getParameter("expire");
        String sig = request.getParameter("sig");
        long expire = 0;
        if (expireStr != null && expireStr.matches("\\d+")) {
            expire = Long.parseLong(expireStr);
        }
        if (signer.verify(path, expire, sig)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("text/plain;charset=UTF-8");
        try {
            response.getWriter().write("图片链接无效或已过期");
        } catch (Exception ignored) {
        }
        return false;
    }
}
