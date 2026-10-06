package org.las.sso.observability;

import org.las.sso.config.MonitoringProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MonitoringSecurityConfiguration {

    public static final String USERNAME = "las-sso-metrics";

    @Bean
    @Order(0)
    SecurityFilterChain monitoringSecurityFilterChain(HttpSecurity http, MonitoringProperties properties,
            Environment environment) throws Exception {
        http.securityMatcher("/actuator/prometheus", "/actuator/prometheus/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable());
        if (!properties.enabled()) {
            // Exposure configuration alone must not let an ordinary browser Session read metrics.
            return http.authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                    .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                            new HttpStatusEntryPoint(HttpStatus.FORBIDDEN))).build();
        }
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        Integer applicationPort = environment.getProperty("server.port", Integer.class, 9000);
        if (managementPort == null || managementPort <= 0 || managementPort.equals(applicationPort)) {
            throw new IllegalStateException("Monitoring requires an explicit separate management port");
        }
        var provider = new DaoAuthenticationProvider(username -> {
            if (!USERNAME.equals(username)) throw new UsernameNotFoundException("Invalid monitoring credentials");
            return User.withUsername(USERNAME).password(properties.passwordHash()).roles("SSO_METRICS").build();
        });
        // Independent provider: no human account, OIDC client or application PasswordEncoder can authenticate a scrape.
        provider.setPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        http.authenticationManager(new ProviderManager(provider))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().access((authentication, context) ->
                        new AuthorizationDecision("GET".equals(context.getRequest().getMethod())
                                && context.getRequest().getLocalPort() == managementPort
                                && authentication.get().getAuthorities().stream()
                                        .anyMatch(authority -> "ROLE_SSO_METRICS".equals(authority.getAuthority())))))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
