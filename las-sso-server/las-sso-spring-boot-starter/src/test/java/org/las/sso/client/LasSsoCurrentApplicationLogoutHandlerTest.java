package org.las.sso.client;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

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
        var registration = ClientRegistration.withRegistrationId("las-sso")
                .clientId("client-a")
                .clientSecret("secret-a")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/callback")
                .authorizationUri("https://sso.example/oauth2/authorize")
                .tokenUri("https://sso.example/oauth2/token")
                .jwkSetUri("https://sso.example/oauth2/jwks")
                .providerConfigurationMetadata(java.util.Map.of(
                        "revocation_endpoint", "https://sso.example/oauth2/revoke"))
                .build();
        var registrations = mock(ClientRegistrationRepository.class);
        var authorizedClients = mock(OAuth2AuthorizedClientRepository.class);
        var revocations = mock(LasSsoTokenRevocationQueue.class);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "user-a", "n/a", java.util.List.of());
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "access-a",
                Instant.now(),
                Instant.now().plusSeconds(900));
        var refreshToken = new OAuth2RefreshToken(
                "refresh-a", Instant.now(), Instant.now().plusSeconds(3600));
        var authorizedClient = new OAuth2AuthorizedClient(
                registration, authentication.getName(), accessToken, refreshToken);
        when(registrations.findByRegistrationId("las-sso")).thenReturn(registration);
        when(authorizedClients.loadAuthorizedClient("las-sso", authentication, request))
                .thenReturn(authorizedClient);
        var handler = new LasSsoCurrentApplicationLogoutHandler(
                registrations,
                authorizedClients,
                new LasSsoClientProperties("las-sso", Duration.ofSeconds(60)),
                revocations);

        handler.logout(request, response, authentication);

        verify(revocations).enqueue(registration, "refresh-a", "refresh_token");
        verify(authorizedClients).removeAuthorizedClient(
                "las-sso", authentication, request, response);
    }
}
