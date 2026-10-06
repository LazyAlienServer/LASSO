package org.las.sso.consumer;

import org.las.sso.client.LasSsoSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Deliberately outside the starter package: a consumer must not depend on hidden filters. */
@Configuration(proxyBeanMethods = false)
public class ApplicationOwnedSecurityConfiguration {

    @Bean
    SecurityFilterChain applicationChain(HttpSecurity http, LasSsoSecurityConfigurer las) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/internal/oidc/backchannel-logout", "/error", "/app-success", "/api/auth/login").permitAll()
                .requestMatchers("/app/admin").hasRole("BUSINESS_ADMIN")
                .anyRequest().authenticated());
        las.configure(http, (request, response, authentication) -> {
            response.setHeader("X-Application-Success", "called");
            response.sendRedirect("/app-success");
        });
        return http.build();
    }
}
