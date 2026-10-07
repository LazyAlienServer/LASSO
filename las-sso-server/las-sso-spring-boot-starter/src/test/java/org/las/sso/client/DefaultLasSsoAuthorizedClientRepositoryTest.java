package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

class DefaultLasSsoAuthorizedClientRepositoryTest {

    @Test
    void authorizedClientsAreIsolatedByLocalHttpSessionEvenForTheSamePrincipal() {
        var repository = new DefaultLasSsoAuthorizedClientRepository();
        Authentication principal =
                UsernamePasswordAuthenticationToken.authenticated("same-subject", null, List.of());
        ClientRegistration registration =
                ClientRegistration.withRegistrationId("las-sso")
                        .clientId("laslog-bff")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://client.example/callback")
                        .authorizationUri("https://sso.example/oauth2/authorize")
                        .tokenUri("https://sso.example/oauth2/token")
                        .build();
        OAuth2AuthorizedClient sessionAClient =
                client(registration, principal.getName(), "access-a");
        OAuth2AuthorizedClient sessionBClient =
                client(registration, principal.getName(), "access-b");

        MockHttpServletRequest requestA = request(new MockHttpSession());
        MockHttpServletRequest requestB = request(new MockHttpSession());
        MockHttpServletResponse responseA = new MockHttpServletResponse();
        MockHttpServletResponse responseB = new MockHttpServletResponse();

        repository.saveAuthorizedClient(sessionAClient, principal, requestA, responseA);
        repository.saveAuthorizedClient(sessionBClient, principal, requestB, responseB);

        assertThat(
                        repository
                                .loadAuthorizedClient("las-sso", principal, requestA)
                                .getAccessToken()
                                .getTokenValue())
                .isEqualTo("access-a");
        assertThat(
                        repository
                                .loadAuthorizedClient("las-sso", principal, requestB)
                                .getAccessToken()
                                .getTokenValue())
                .isEqualTo("access-b");

        repository.removeAuthorizedClient("las-sso", principal, requestA, responseA);
        OAuth2AuthorizedClient afterA =
                repository.loadAuthorizedClient("las-sso", principal, requestA);
        OAuth2AuthorizedClient afterB =
                repository.loadAuthorizedClient("las-sso", principal, requestB);
        assertThat(afterA).isNull();
        assertThat(afterB.getAccessToken().getTokenValue()).isEqualTo("access-b");
    }

    private static MockHttpServletRequest request(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    private static OAuth2AuthorizedClient client(
            ClientRegistration registration, String principalName, String token) {
        Instant issuedAt = Instant.parse("2026-10-02T00:00:00Z");
        return new OAuth2AuthorizedClient(
                registration,
                principalName,
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        token,
                        issuedAt,
                        issuedAt.plusSeconds(900)));
    }
}
