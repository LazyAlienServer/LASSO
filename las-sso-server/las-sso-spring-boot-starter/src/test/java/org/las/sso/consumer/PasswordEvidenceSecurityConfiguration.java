package org.las.sso.consumer;

import org.las.sso.client.LasSsoReturnToSuccessHandler;
import org.las.sso.client.LasSsoSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

/** Package-external consumer exercises standard params and verified server-side evidence access. */
@Configuration(proxyBeanMethods = false)
public class PasswordEvidenceSecurityConfiguration {

    @Bean
    SecurityFilterChain passwordEvidenceChain(HttpSecurity http, LasSsoSecurityConfigurer las)
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
                    var user =
                            (OidcUser) ((OAuth2AuthenticationToken) authentication).getPrincipal();
                    Object time = user.getIdToken().getClaims().get("auth_time");
                    if (time != null) response.setHeader("X-Fixture-Auth-Time", time.toString());
                    response.setHeader("X-Consumer-Success", "checked");
                    redirect.onAuthenticationSuccess(request, response, authentication);
                },
                builder ->
                        builder.additionalParameters(
                                parameters -> {
                                    parameters.put("prompt", "login");
                                    parameters.put("max_age", "0");
                                }));
        return http.build();
    }
}
