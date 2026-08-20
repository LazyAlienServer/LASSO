package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.las.sso.identity.LasUserPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

class AuthorizationServerConfigurationClaimsTest {

    @Test
    void accessTokenDoesNotReceiveProfileClaims() {
        JwtClaimsSet claims = customize(OAuth2TokenType.ACCESS_TOKEN, Set.of("openid", "profile"));

        assertThat(claims.getClaims()).doesNotContainKey("preferred_username");
        assertThat(claims.getAudience()).containsExactly("urn:las:resource:test");
    }

    @Test
    void idTokenReceivesAuthenticationClaimsAndProfileOnlyWhenApproved() {
        JwtClaimsSet withoutProfile = customize(
                new OAuth2TokenType(OidcParameterNames.ID_TOKEN), Set.of("openid"));
        JwtClaimsSet withProfile = customize(
                new OAuth2TokenType(OidcParameterNames.ID_TOKEN), Set.of("openid", "profile"));

        assertThat((Object) withoutProfile.getClaim("acr")).isEqualTo("urn:las:loa:1");
        assertThat(withoutProfile.<List<String>>getClaim("amr")).containsExactly("pwd");
        assertThat(withoutProfile.getClaims()).doesNotContainKey("preferred_username");
        assertThat((Object) withProfile.getClaim("preferred_username")).isEqualTo("CaseSensitiveUser");
    }

    private static JwtClaimsSet customize(OAuth2TokenType tokenType, Set<String> scopes) {
        var context = mock(JwtEncodingContext.class);
        var claims = JwtClaimsSet.builder()
                .issuedAt(Instant.parse("2026-08-20T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-20T00:15:00Z"));
        var principal = new LasUserPrincipal(
                UUID.fromString("019c10a5-b800-7000-8000-000000000001"),
                "CaseSensitiveUser", "{noop}irrelevant", true, List.of());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of());

        when(context.getClaims()).thenReturn(claims);
        when(context.getTokenType()).thenReturn(tokenType);
        when(context.getAuthorizedScopes()).thenReturn(scopes);
        when(context.getPrincipal()).thenReturn(authentication);
        when(context.getRegisteredClient()).thenReturn(RegisteredClient.withId("registered-id")
                .clientId("test-client")
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/callback")
                .scope("openid")
                .build());

        JdbcClient jdbcClient = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbcClient.sql(anyString()).param(anyString(), any())
                .query(String.class).list()).thenReturn(List.of("urn:las:resource:test"));
        new AuthorizationServerConfiguration().identityClaimsCustomizer(jdbcClient).customize(context);
        return claims.build();
    }
}
