package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class LasSsoAuthorizedClientRefreshFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedRequestRefreshesBeforeContinuingTheChain() throws Exception {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        var refreshed = new AtomicBoolean();
        var repository = mock(OAuth2AuthorizedClientRepository.class);
        var filter = new LasSsoAuthorizedClientRefreshFilter(
                new LasSsoClientProperties("las-sso", null),
                (authentication, request, response) -> refreshed.set(true),
                repository,
                Clock.fixed(now, ZoneOffset.UTC));
        var authentication = authentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var request = new MockHttpServletRequest("GET", "/protected");
        when(repository.loadAuthorizedClient("las-sso", authentication, request))
                .thenReturn(authorizedClient(now.plusSeconds(1)));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(refreshed).isTrue();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void expiredFreshnessBoundaryInvalidatesTheSessionAndReturnsNoStore401() throws Exception {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        var repository = mock(OAuth2AuthorizedClientRepository.class);
        var authentication = authentication();
        var filter = new LasSsoAuthorizedClientRefreshFilter(
                new LasSsoClientProperties("las-sso", null),
                (ignoredAuthentication, request, response) -> {
                },
                repository,
                Clock.fixed(now, ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var request = new MockHttpServletRequest("GET", "/api/private-data");
        when(repository.loadAuthorizedClient("las-sso", authentication, request))
                .thenReturn(authorizedClient(now));
        var session = new MockHttpSession();
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(repository).removeAuthorizedClient("las-sso", authentication, request, response);
        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("SESSION_EXPIRED");
    }

    @Test
    void fourteenDayAbsoluteSessionBoundaryExpiresBeforeTokenRefresh() throws Exception {
        var session = new MockHttpSession();
        Instant now = Instant.ofEpochMilli(session.getCreationTime())
                .plus(java.time.Duration.ofDays(14));
        var refreshed = new AtomicBoolean();
        var repository = mock(OAuth2AuthorizedClientRepository.class);
        var authentication = authentication();
        var filter = new LasSsoAuthorizedClientRefreshFilter(
                new LasSsoClientProperties("las-sso", null),
                (ignoredAuthentication, request, response) -> refreshed.set(true),
                repository,
                Clock.fixed(now, ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var request = new MockHttpServletRequest("GET", "/api/private-data");
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(refreshed).isFalse();
        verify(repository).removeAuthorizedClient("las-sso", authentication, request, response);
        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void apiRefreshFailureRemovesTheClientInvalidatesTheSessionAndReturnsNoStore401()
            throws Exception {
        var repository = mock(OAuth2AuthorizedClientRepository.class);
        var filter = new LasSsoAuthorizedClientRefreshFilter(
                new LasSsoClientProperties("las-sso", null),
                (authentication, request, response) -> {
                    throw new OAuth2AuthorizationException(new OAuth2Error("invalid_grant"));
                },
                repository);
        var authentication = authentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var request = new MockHttpServletRequest("GET", "/api/private-data");
        var session = new MockHttpSession();
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(repository).removeAuthorizedClient("las-sso", authentication, request, response);
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("SESSION_EXPIRED");
    }

    @Test
    void htmlRefreshFailureRedirectsToTheLoginEntryPoint() throws Exception {
        var filter = new LasSsoAuthorizedClientRefreshFilter(
                new LasSsoClientProperties("las-sso", null),
                (authentication, request, response) -> {
                    throw new OAuth2AuthorizationException(new OAuth2Error("invalid_grant"));
                },
                mock(OAuth2AuthorizedClientRepository.class));
        SecurityContextHolder.getContext().setAuthentication(authentication());
        var request = new MockHttpServletRequest("GET", "/protected");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getRedirectedUrl()).isEqualTo("/api/auth/login");
    }

    @Test
    void transientSsoFailurePreservesTheSessionAndReturnsNoStore503() throws Exception {
        var repository = mock(OAuth2AuthorizedClientRepository.class);
        var filter = new LasSsoAuthorizedClientRefreshFilter(
                new LasSsoClientProperties("las-sso", null),
                (authentication, request, response) -> {
                    throw new OAuth2AuthorizationException(new OAuth2Error("server_error"));
                },
                repository);
        SecurityContextHolder.getContext().setAuthentication(authentication());
        var request = new MockHttpServletRequest("GET", "/protected");
        var session = new MockHttpSession();
        request.setSession(session);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verifyNoInteractions(repository);
        assertThat(session.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("SSO_UNAVAILABLE");
    }

    private static OAuth2AuthenticationToken authentication() {
        Instant now = Instant.now();
        var idToken = new OidcIdToken("server-side-only", now, now.plusSeconds(600), Map.of(
                IdTokenClaimNames.ISS, "http://127.0.0.1:9000",
                IdTokenClaimNames.SUB, "019fc000-0000-7000-8000-000000000001",
                IdTokenClaimNames.AUD, List.of("laslog-bff"),
                "preferred_username", "CaseSensitiveUser"));
        var principal = new DefaultOidcUser(List.of(), idToken, "preferred_username");
        return new OAuth2AuthenticationToken(principal, List.of(), "las-sso");
    }

    private static OAuth2AuthorizedClient authorizedClient(Instant expiresAt) {
        var registration = ClientRegistration.withRegistrationId("las-sso")
                .clientId("laslog-bff")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:8088/login/oauth2/code/las-sso")
                .authorizationUri("http://127.0.0.1:9000/oauth2/authorize")
                .tokenUri("http://127.0.0.1:9000/oauth2/token")
                .build();
        var accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "server-side-access-token",
                expiresAt.minusSeconds(60),
                expiresAt);
        return new OAuth2AuthorizedClient(registration, "user-id", accessToken);
    }
}
