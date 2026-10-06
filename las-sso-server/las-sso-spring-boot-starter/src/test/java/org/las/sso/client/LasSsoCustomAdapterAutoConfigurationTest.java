package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class LasSsoCustomAdapterAutoConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LasSsoClientAutoConfiguration.class))
            .withUserConfiguration(CustomAdapters.class);

    @Test
    void customSessionTokenReplayAndRevocationAdaptersReplaceDefaults() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(LasSsoAuthorizedClientRepository.class))
                    .isSameAs(CustomAdapters.TOKEN_REPOSITORY);
            assertThat(application.getBean(LasSsoBackChannelSessionStore.class))
                    .isSameAs(CustomAdapters.SESSION_STORE);
            assertThat(application.getBean(LasSsoLogoutReplayStore.class))
                    .isSameAs(CustomAdapters.REPLAY_STORE);
            assertThat(application.getBean(LasSsoRevocationTaskSink.class))
                    .isSameAs(CustomAdapters.REVOCATION_SINK);
            assertThat(application.getBean(LasSsoBackChannelLogoutController.class))
                    .isNotNull();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAdapters {
        static final LasSsoAuthorizedClientRepository TOKEN_REPOSITORY =
                new NoOpTokenRepository();
        static final LasSsoBackChannelSessionStore SESSION_STORE = new NoOpSessionStore();
        static final LasSsoLogoutReplayStore REPLAY_STORE =
                (issuer, tokenId, digest, expiresAt) -> true;
        static final LasSsoRevocationTaskSink REVOCATION_SINK =
                (registration, token, tokenTypeHint) -> { };

        @Bean
        LasSsoAuthorizedClientRepository tokenRepository() {
            return TOKEN_REPOSITORY;
        }

        @Bean
        LasSsoBackChannelSessionStore sessionStore() {
            return SESSION_STORE;
        }

        @Bean
        LasSsoLogoutReplayStore replayStore() {
            return REPLAY_STORE;
        }

        @Bean
        LasSsoRevocationTaskSink revocationSink() {
            return REVOCATION_SINK;
        }

        @Bean
        ClientRegistrationRepository registrations() {
            ClientRegistration registration = ClientRegistration.withRegistrationId("las-sso")
                    .clientId("test-client")
                    .clientSecret("test-secret")
                    .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://client.example/callback")
                    .authorizationUri("https://sso.example/oauth2/authorize")
                    .tokenUri("https://sso.example/oauth2/token")
                    .jwkSetUri("https://sso.example/oauth2/jwks")
                    .issuerUri("https://sso.example")
                    .build();
            return registrationId -> "las-sso".equals(registrationId) ? registration : null;
        }

        @Bean(name = "lasSsoLogoutTokenDecoder")
        JwtDecoder logoutTokenDecoder() {
            return token -> {
                throw new UnsupportedOperationException("test decoder is not invoked");
            };
        }

        @Bean
        OAuth2AuthorizedClientManager authorizedClientManager() {
            return mock(OAuth2AuthorizedClientManager.class);
        }

        @Bean
        LasSsoAuthorizedClientRefresher authorizedClientRefresher() {
            return (authentication, request, response) -> { };
        }

        @Bean
        SecurityFilterChain securityFilterChain() {
            return mock(SecurityFilterChain.class);
        }
    }

    private static final class NoOpTokenRepository
            implements LasSsoAuthorizedClientRepository {
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String clientRegistrationId,
                org.springframework.security.core.Authentication principal,
                HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(
                OAuth2AuthorizedClient authorizedClient,
                org.springframework.security.core.Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {
        }

        @Override
        public void removeAuthorizedClient(
                String clientRegistrationId,
                org.springframework.security.core.Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {
        }
    }

    private static final class NoOpSessionStore implements LasSsoBackChannelSessionStore {
        @Override
        public void register(String sid, String subject, HttpSession session) {
        }

        @Override
        public int invalidate(String sid, String subject) {
            return 0;
        }

        @Override
        public void unregister(String sessionId) {
        }
    }
}
