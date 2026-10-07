package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

class LasSsoCurrentApplicationLogoutHandlerTest {

    @Test
    void removesLocalAuthorizationAndQueuesRefreshTokenRevocation() {
        var registration =
                ClientRegistration.withRegistrationId("las-sso")
                        .clientId("client-a")
                        .clientSecret("secret-a")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://client.example/callback")
                        .authorizationUri("https://sso.example/oauth2/authorize")
                        .tokenUri("https://sso.example/oauth2/token")
                        .jwkSetUri("https://sso.example/oauth2/jwks")
                        .providerConfigurationMetadata(
                                java.util.Map.of(
                                        "revocation_endpoint", "https://sso.example/oauth2/revoke"))
                        .build();
        var registrations = mock(ClientRegistrationRepository.class);
        var authorizedClients = mock(OAuth2AuthorizedClientRepository.class);
        var revocations = mock(LasSsoTokenRevocationQueue.class);
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "user-a", "n/a", java.util.List.of());
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var accessToken =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "access-a",
                        Instant.now(),
                        Instant.now().plusSeconds(900));
        var refreshToken =
                new OAuth2RefreshToken("refresh-a", Instant.now(), Instant.now().plusSeconds(3600));
        var authorizedClient =
                new OAuth2AuthorizedClient(
                        registration, authentication.getName(), accessToken, refreshToken);
        when(registrations.findByRegistrationId("las-sso")).thenReturn(registration);
        when(authorizedClients.loadAuthorizedClient("las-sso", authentication, request))
                .thenReturn(authorizedClient);
        var handler =
                new LasSsoCurrentApplicationLogoutHandler(
                        registrations,
                        authorizedClients,
                        new LasSsoClientProperties("las-sso", Duration.ofSeconds(60)),
                        revocations);

        handler.logout(request, response, authentication);

        verify(revocations).enqueue(registration, "refresh-a", "refresh_token");
        verify(authorizedClients)
                .removeAuthorizedClient("las-sso", authentication, request, response);
    }

    @Test
    void customRevocationSinkReceivesWorkAfterLocalAuthorizationRemoval() {
        var registration =
                ClientRegistration.withRegistrationId("las-sso")
                        .clientId("client-a")
                        .clientSecret("secret-a")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://client.example/callback")
                        .authorizationUri("https://sso.example/oauth2/authorize")
                        .tokenUri("https://sso.example/oauth2/token")
                        .jwkSetUri("https://sso.example/oauth2/jwks")
                        .providerConfigurationMetadata(
                                java.util.Map.of(
                                        "revocation_endpoint", "https://sso.example/oauth2/revoke"))
                        .build();
        var registrations = mock(ClientRegistrationRepository.class);
        var authorizedClients = mock(OAuth2AuthorizedClientRepository.class);
        var order = new ArrayList<String>();
        LasSsoRevocationTaskSink sink = (ignored, token, hint) -> order.add("remote:" + token);
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "user-a", "n/a", java.util.List.of());
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        doAnswer(
                        invocation -> {
                            order.add("local:removed");
                            return null;
                        })
                .when(authorizedClients)
                .removeAuthorizedClient("las-sso", authentication, request, response);
        var accessToken =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "access-a",
                        Instant.now(),
                        Instant.now().plusSeconds(900));
        var refreshToken =
                new OAuth2RefreshToken("refresh-a", Instant.now(), Instant.now().plusSeconds(3600));
        when(registrations.findByRegistrationId("las-sso")).thenReturn(registration);
        when(authorizedClients.loadAuthorizedClient("las-sso", authentication, request))
                .thenReturn(
                        new OAuth2AuthorizedClient(
                                registration, authentication.getName(), accessToken, refreshToken));
        var handler =
                new LasSsoCurrentApplicationLogoutHandler(
                        registrations,
                        authorizedClients,
                        new LasSsoClientProperties("las-sso", Duration.ofSeconds(60)),
                        sink);

        handler.logout(request, response, authentication);

        verify(authorizedClients)
                .removeAuthorizedClient("las-sso", authentication, request, response);
        assertThat(order).containsExactly("local:removed", "remote:refresh-a");
        assertThat(request.getAttribute(LasSsoRevocationSubmission.REQUEST_ATTRIBUTE))
                .isEqualTo(LasSsoRevocationSubmission.ACCEPTED);
    }

    @Test
    void acceptanceFailureIsVisibleAfterSessionInvalidationAndDoesNotExposeTokens() {
        var registration =
                ClientRegistration.withRegistrationId("las-sso")
                        .clientId("client-a")
                        .clientSecret("fixture-secret")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://client.example/callback")
                        .authorizationUri("https://issuer.example/authorize")
                        .tokenUri("https://issuer.example/token")
                        .build();
        var repository = mock(OAuth2AuthorizedClientRepository.class);
        var registrations = mock(ClientRegistrationRepository.class);
        var request = new MockHttpServletRequest();
        request.getSession();
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "subject", null, java.util.List.of());
        var access =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "fixture-token",
                        Instant.now(),
                        Instant.now().plusSeconds(900));
        when(repository.loadAuthorizedClient("las-sso", authentication, request))
                .thenReturn(new OAuth2AuthorizedClient(registration, "subject", access));
        when(registrations.findByRegistrationId("las-sso")).thenReturn(registration);
        LasSsoRevocationTaskSink sink =
                (client, token, hint) -> {
                    assertThat(request.getSession(false)).isNull();
                    throw new IllegalStateException("fixture secret-bearing error");
                };
        new LasSsoCurrentApplicationLogoutHandler(
                        registrations,
                        repository,
                        new LasSsoClientProperties("las-sso", Duration.ofSeconds(60)),
                        sink)
                .logout(request, new MockHttpServletResponse(), authentication);
        assertThat(request.getSession(false)).isNull();
        assertThat(request.getAttribute(LasSsoRevocationSubmission.REQUEST_ATTRIBUTE))
                .isEqualTo(LasSsoRevocationSubmission.FAILED);
    }

    @Test
    void unauthenticatedLogoutRecordsNoTokenWithoutSubmittingWork() {
        var request = new MockHttpServletRequest();
        new LasSsoCurrentApplicationLogoutHandler(
                        mock(ClientRegistrationRepository.class),
                        mock(OAuth2AuthorizedClientRepository.class),
                        new LasSsoClientProperties("las-sso", Duration.ofSeconds(60)),
                        (LasSsoRevocationTaskSink)
                                (client, token, hint) -> {
                                    throw new AssertionError("Unexpected work");
                                })
                .logout(request, new MockHttpServletResponse(), null);
        assertThat(request.getAttribute(LasSsoRevocationSubmission.REQUEST_ATTRIBUTE))
                .isEqualTo(LasSsoRevocationSubmission.NO_TOKEN);
    }
}
