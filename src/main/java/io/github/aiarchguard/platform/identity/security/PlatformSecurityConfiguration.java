package io.github.aiarchguard.platform.identity.security;

import io.github.aiarchguard.platform.common.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** 所有 profile 共享的无状态安全基线；未启用 oidc 时没有临时用户兜底，业务入口默认拒绝。 */
@Configuration(proxyBeanMethods = false)
class PlatformSecurityConfiguration {

    @Bean
    @Profile("!oidc")
    SecurityFilterChain failClosedSecurityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter) throws Exception {
        configureDefaults(http, errorWriter);
        return http.build();
    }

    /** 健康检查公开；Webhook 交给签名验证入口，其他请求必须认证，Project 权限由应用层继续检查。 */
    static void configureDefaults(HttpSecurity http, ApiErrorWriter errorWriter) throws Exception {
        http
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .csrf(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> errorWriter.write(
                    response,
                    HttpStatus.UNAUTHORIZED.value(),
                    "authentication.required",
                    "Authentication is required."
                ))
                .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                    response,
                    HttpStatus.FORBIDDEN.value(),
                    "authorization.denied",
                    "The current actor is not allowed to perform this action."
                )))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/github/webhooks").permitAll()
                .anyRequest().authenticated());
    }

    /** 空用户源阻止 Spring Boot 自动创建带随机密码的默认用户。 */
    @Bean
    UserDetailsService emptyUserDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
