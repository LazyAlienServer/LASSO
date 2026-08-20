package org.las.sso.client;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.http.HttpMethod;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

@AutoConfiguration
@EnableConfigurationProperties(LasSsoClientProperties.class)
public class LasSsoClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LasSsoSessionController lasSsoSessionController(LasSsoClientProperties properties) {
        return new LasSsoSessionController(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    LasSsoGlobalLogoutController lasSsoGlobalLogoutController(
            ClientRegistrationRepository registrations,
            LasSsoClientProperties properties) {
        return new LasSsoGlobalLogoutController(registrations, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    LasSsoBackChannelSessionRegistry lasSsoBackChannelSessionRegistry() {
        return new LasSsoBackChannelSessionRegistry();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    LasSsoTokenRevocationQueue lasSsoTokenRevocationQueue() {
        return new LasSsoTokenRevocationQueue();
    }

    @Bean
    @ConditionalOnMissingBean
    LasSsoCurrentApplicationLogoutHandler lasSsoCurrentApplicationLogoutHandler(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            LasSsoClientProperties properties,
            LasSsoTokenRevocationQueue revocations) {
        return new LasSsoCurrentApplicationLogoutHandler(
                registrations, authorizedClients, properties, revocations);
    }

    @Bean
    @ConditionalOnMissingBean(name = "lasSsoSessionLifecycleListener")
    ServletListenerRegistrationBean<HttpSessionListener> lasSsoSessionLifecycleListener(
            LasSsoBackChannelSessionRegistry sessions) {
        HttpSessionListener listener = new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                sessions.unregister(event.getSession().getId());
            }
        };
        return new ServletListenerRegistrationBean<>(listener);
    }

    @Bean
    @ConditionalOnMissingBean
    LasSsoBackChannelLogoutController lasSsoBackChannelLogoutController(
            @Qualifier("lasSsoLogoutTokenDecoder") JwtDecoder jwtDecoder,
            ClientRegistrationRepository registrations,
            LasSsoClientProperties properties,
            LasSsoBackChannelSessionRegistry sessions) {
        var registration = registrations.findByRegistrationId(properties.registrationId());
        if (registration == null) {
            throw new IllegalStateException("LAS SSO client registration is missing");
        }
        return new LasSsoBackChannelLogoutController(
                jwtDecoder, registration.getClientId(), sessions);
    }

    @Bean
    @ConditionalOnMissingBean(name = "lasSsoLogoutTokenDecoder")
    JwtDecoder lasSsoLogoutTokenDecoder(
            ClientRegistrationRepository registrations,
            LasSsoClientProperties properties) {
        var registration = registrations.findByRegistrationId(properties.registrationId());
        if (registration == null) {
            throw new IllegalStateException("LAS SSO client registration is missing");
        }
        String jwkSetUri = registration.getProviderDetails().getJwkSetUri();
        String issuerUri = registration.getProviderDetails().getIssuerUri();
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .validateType(false)
                // Nimbus otherwise accepts only typ=JWT before the application
                // validator runs. Back-Channel Logout requires typ=logout+jwt.
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("logout+jwt"))))
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(issuerUri),
                new JwtTypeValidator("logout+jwt")));
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain lasSsoSecurityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            LasSsoClientProperties properties,
            LasSsoAuthorizedClientRefresher refresher,
            LasSsoBackChannelSessionRegistry backChannelSessions,
            LasSsoCurrentApplicationLogoutHandler currentApplicationLogoutHandler) throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/error", "/assets/**", "/api/auth/login", "/api/auth/session",
                                "/logged-out", "/internal/oidc/backchannel-logout")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(resolver))
                        .defaultSuccessUrl("/", true))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessUrl(properties.logoutSuccessUri())
                        .addLogoutHandler(currentApplicationLogoutHandler)
                        .invalidateHttpSession(true)
                        .deleteCookies(properties.sessionCookieName()));
        http.csrf(csrf -> csrf.ignoringRequestMatchers(
                PathPatternRequestMatcher.pathPattern(
                        HttpMethod.POST, "/internal/oidc/backchannel-logout")));
        http.addFilterAfter(
                new LasSsoAuthorizedClientRefreshFilter(properties, refresher, authorizedClients),
                OAuth2LoginAuthenticationFilter.class);
        http.addFilterAfter(
                new LasSsoSessionRegistrationFilter(backChannelSessions),
                LasSsoAuthorizedClientRefreshFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean(OAuth2AuthorizedClientManager.class)
    OAuth2AuthorizedClientManager lasSsoAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            LasSsoClientProperties properties) {
        var manager = new DefaultOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken(refresh -> refresh.clockSkew(properties.refreshBeforeExpiry()))
                .build());
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean(LasSsoAuthorizedClientRefresher.class)
    LasSsoAuthorizedClientRefresher lasSsoAuthorizedClientRefresher(
            OAuth2AuthorizedClientManager authorizedClientManager,
            LasSsoClientProperties properties) {
        return new SingleFlightLasSsoAuthorizedClientRefresher(authorizedClientManager, properties);
    }
}
