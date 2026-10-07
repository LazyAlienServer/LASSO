package org.las.sso.consumer;

import org.las.sso.client.LasSsoReturnTo;
import org.las.sso.client.LasSsoReturnToSuccessHandler;
import org.las.sso.client.LasSsoSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

/** Consumer business-success code runs before explicitly selecting the final target redirect. */
@Configuration(proxyBeanMethods = false)
public class ReturnToSecurityConfiguration {
    @Bean
    SecurityFilterChain returnToChain(HttpSecurity http, LasSsoSecurityConfigurer las)
            throws Exception {
        http.authorizeHttpRequests(
                authorize ->
                        authorize
                                .requestMatchers(
                                        "/api/auth/login",
                                        "/error",
                                        "/internal/oidc/backchannel-logout")
                                .permitAll()
                                .anyRequest()
                                .authenticated());
        var redirect =
                new LasSsoReturnToSuccessHandler(
                        new SimpleUrlAuthenticationSuccessHandler("/app-success"));
        las.configure(
                http,
                (request, response, authentication) -> {
                    response.setHeader("X-Business-Success", "checked");
                    if (LasSsoReturnTo.target(request).filter("/blocked"::equals).isPresent()) {
                        response.setStatus(
                                403); // Fixture business check: do not reach the final redirect
                        // hook.
                        return;
                    }
                    LasSsoReturnTo.target(request)
                            .ifPresent(target -> response.setHeader("X-Verified-Target", target));
                    redirect.onAuthenticationSuccess(request, response, authentication);
                });
        return http.build();
    }
}
