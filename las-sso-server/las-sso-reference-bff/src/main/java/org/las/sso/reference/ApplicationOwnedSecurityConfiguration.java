package org.las.sso.reference;

import org.las.sso.client.LasSsoReturnToSuccessHandler;
import org.las.sso.client.LasSsoSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

/** Optional sample of the same public composition used by application-owned BFF security. */
@Configuration(proxyBeanMethods = false)
@Profile("application-security")
public class ApplicationOwnedSecurityConfiguration {

    @Bean
    SecurityFilterChain referenceSecurityFilterChain(
            HttpSecurity http, LasSsoSecurityConfigurer las) throws Exception {
        http.authorizeHttpRequests(
                authorize ->
                        authorize
                                .requestMatchers(
                                        "/",
                                        "/error",
                                        "/assets/**",
                                        "/api/auth/login",
                                        "/api/auth/session",
                                        "/logged-out",
                                        "/internal/oidc/backchannel-logout")
                                .permitAll()
                                .anyRequest()
                                .authenticated());
        var success = new SimpleUrlAuthenticationSuccessHandler("/protected");
        success.setAlwaysUseDefaultTargetUrl(true);
        las.configure(http, new LasSsoReturnToSuccessHandler(success));
        return http.build();
    }
}
