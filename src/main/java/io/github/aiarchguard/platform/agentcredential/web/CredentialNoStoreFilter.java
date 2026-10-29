package io.github.aiarchguard.platform.agentcredential.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在安全链之前为凭据固定路径设置禁止缓存头，使认证失败响应也不被浏览器缓存。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
final class CredentialNoStoreFilter extends OncePerRequestFilter {
    /** 仅匹配当前上下文内凭据路径，继续原过滤链并保留其状态及错误处理。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().equals(request.getContextPath() + "/api/v1/agent/credentials/deepseek")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
