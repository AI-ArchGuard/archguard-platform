package io.github.aiarchguard.platform.governance.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 将治理失败写入审计拦截器接入 Spring MVC；具体路由和失败方法由拦截器内部筛选。
 * 该 MVC 配置不能覆盖在身份过滤层就被拒绝、没有进入 MVC 的请求。
 */
@Configuration
class GovernanceAuditWebConfiguration implements WebMvcConfigurer {
    private final GovernanceWriteAuditInterceptor audit;

    GovernanceAuditWebConfiguration(GovernanceWriteAuditInterceptor audit) {
        this.audit = audit;
    }

    /** 注册完成回调，不在此给所有 MVC 请求都记录治理审计。 */
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(audit);
    }
}
