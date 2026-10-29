package io.github.aiarchguard.platform.agent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 为设置、确认、批次和真实入口统一添加禁止缓存响应头，连同后续错误响应一起覆盖，减少个人控制信息被缓存。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
final class EnablementNoStoreFilter extends OncePerRequestFilter {
    /** 进入后续过滤器/控制器前设置头部，因此匹配路由上的错误响应也禁止缓存；本过滤器不负责授权。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.matches("/api/v1/projects/[^/]+/agent/(settings|enablements(?:/.*)?|batches(?:/.*)?|live-requests(?:/.*)?)")) {
            response.setHeader("Cache-Control", "no-store"); response.setHeader("Pragma", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
