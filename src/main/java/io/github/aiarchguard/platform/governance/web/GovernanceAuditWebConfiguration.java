package io.github.aiarchguard.platform.governance.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class GovernanceAuditWebConfiguration implements WebMvcConfigurer {
    private final GovernanceWriteAuditInterceptor audit;

    GovernanceAuditWebConfiguration(GovernanceWriteAuditInterceptor audit) {
        this.audit = audit;
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(audit);
    }
}
