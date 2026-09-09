package io.github.aiarchguard.platform.identity.security;

import io.github.aiarchguard.platform.common.ApiErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class PlatformSecurityConfiguration {

    @Bean
    SecurityFilterChain platformSecurityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter) throws Exception {
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
                .anyRequest().authenticated());

        return http.build();
    }

    @Bean
    UserDetailsService emptyUserDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
