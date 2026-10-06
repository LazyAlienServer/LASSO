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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

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
    @ConditionalOnMissingBean(LasSsoAuthorizedClientRepository.class)
    LasSsoAuthorizedClientRepository lasSsoAuthorizedClientRepository() {
        return new DefaultLasSsoAuthorizedClientRepository();
    }

    @Bean
    @ConditionalOnMissingBean(LasSsoBackChannelSessionStore.class)
    LasSsoBackChannelSessionStore lasSsoBackChannelSessionRegistry(LasSsoClientProperties properties) {
        return new LasSsoBackChannelSessionRegistry(properties.sessionAbsoluteLifetime());
    }

    @Bean
    @ConditionalOnMissingBean(LasSsoLogoutReplayStore.class)
    LasSsoLogoutReplayStore lasSsoLogoutReplayStore() {
        return new DefaultLasSsoLogoutReplayStore();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(LasSsoRevocationTaskSink.class)
    LasSsoRevocationTaskSink lasSsoTokenRevocationQueue() {
        return new LasSsoTokenRevocationQueue();
    }

    @Bean
    @ConditionalOnMissingBean
    LasSsoCurrentApplicationLogoutHandler lasSsoCurrentApplicationLogoutHandler(
            ClientRegistrationRepository registrations,
            LasSsoAuthorizedClientRepository authorizedClients,
            LasSsoClientProperties properties,
            LasSsoRevocationTaskSink revocations) {
        return new LasSsoCurrentApplicationLogoutHandler(
                registrations, authorizedClients, properties, revocations);
    }

    @Bean
    @ConditionalOnMissingBean(name = "lasSsoSessionLifecycleListener")
    ServletListenerRegistrationBean<LasSsoSessionLifecycleListener> lasSsoSessionLifecycleListener(
            LasSsoBackChannelSessionStore sessions, LasSsoClientProperties properties) {
        return new ServletListenerRegistrationBean<>(new LasSsoSessionLifecycleListener(sessions, properties));
    }

    @Bean
    @ConditionalOnMissingBean(LasSsoClientErrorHandler.class)
    LasSsoClientErrorHandler lasSsoClientErrorHandler() {
        return new DefaultLasSsoClientErrorHandler();
    }

    @Bean
    @ConditionalOnMissingBean(LasSsoSecurityConfigurer.class)
    LasSsoSecurityConfigurer lasSsoSecurityConfigurer(
            ClientRegistrationRepository registrations, LasSsoAuthorizedClientRepository authorizedClients,
            LasSsoClientProperties properties, LasSsoAuthorizedClientRefresher refresher,
            LasSsoBackChannelSessionStore sessions, LasSsoCurrentApplicationLogoutHandler logout,
            LasSsoClientErrorHandler errors) {
        return new LasSsoSecurityConfigurer(registrations, authorizedClients, properties,
                refresher, sessions, logout, errors);
    }

    @Bean
    @ConditionalOnMissingBean
    LasSsoBackChannelLogoutController lasSsoBackChannelLogoutController(
            @Qualifier("lasSsoLogoutTokenDecoder") JwtDecoder jwtDecoder,
            ClientRegistrationRepository registrations,
            LasSsoClientProperties properties,
            LasSsoBackChannelSessionStore sessions,
            LasSsoLogoutReplayStore replayStore) {
        var registration = registrations.findByRegistrationId(properties.registrationId());
        if (registration == null) {
            throw new IllegalStateException("LAS SSO client registration is missing");
        }
        String issuer = registration.getProviderDetails().getIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("LAS SSO client registration requires a fixed issuer");
        }
        return new LasSsoBackChannelLogoutController(
                jwtDecoder, registration.getClientId(), sessions, replayStore,
                issuer, java.time.Clock.systemUTC());
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
        return token -> {
            var jwt = decoder.decode(token);
            // Nimbus JWTClaimsSet coerces numeric sub before Spring sees claims. Check the verified
            // payload through Nimbus's own API; do not write a second JWT parser or signature verifier.
            try {
                var raw = com.nimbusds.jwt.SignedJWT.parse(token).getPayload().toJSONObject();
                if (raw == null) throw new IllegalArgumentException("Invalid logout payload");
                for (String name : java.util.List.of("iss", "sub", "jti", "sid")) {
                    if (raw.containsKey(name) && !(raw.get(name) instanceof String)) {
                        throw new IllegalArgumentException("Logout identity claim has invalid type");
                    }
                }
                return jwt;
            } catch (java.text.ParseException | IllegalArgumentException exception) {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("Invalid logout claim types");
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain lasSsoSecurityFilterChain(
            HttpSecurity http,
            LasSsoSecurityConfigurer configurer) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/error", "/assets/**", "/api/auth/login", "/api/auth/session",
                                "/logged-out", "/internal/oidc/backchannel-logout")
                        .permitAll()
                        .anyRequest().authenticated());
        var successHandler = new SimpleUrlAuthenticationSuccessHandler("/");
        successHandler.setAlwaysUseDefaultTargetUrl(true);
        configurer.configure(http, new LasSsoReturnToSuccessHandler(successHandler));
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean(OAuth2AuthorizedClientManager.class)
    OAuth2AuthorizedClientManager lasSsoAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrations,
            LasSsoAuthorizedClientRepository authorizedClients,
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
