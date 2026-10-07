package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.web.OAuth2AuthorizationEndpointFilter;
import org.springframework.web.util.HtmlUtils;

/**
 * Actual dependency filter/renderer; validated authentication result is a fixture, not an app
 * exploit claim.
 */
class DefaultConsentPageDependencyTest {

    @AfterEach
    void clearThreadContexts() {
        SecurityContextHolder.clearContext();
        AuthorizationServerContextHolder.resetContext();
    }

    @ParameterizedTest
    @EnumSource(RenderedField.class)
    void defaultConsentRendererEscapesEveryFixtureControlledField(RenderedField field)
            throws Exception {
        String unsafe = "\"><img src=x onerror=alert('d1')>&";
        String clientId = field == RenderedField.CLIENT_ID ? unsafe : "d1-client";
        String state = field == RenderedField.STATE ? unsafe : "d1-consent-state";
        String principalName = field == RenderedField.PRINCIPAL ? unsafe : "d1-principal";
        String scope =
                field == RenderedField.REQUESTED_SCOPE || field == RenderedField.APPROVED_SCOPE
                        ? unsafe
                        : "profile";
        var principal = new TestingAuthenticationToken(principalName, "fixture", "ROLE_USER");
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(principal);
        SecurityContextHolder.setContext(security);
        var settings = AuthorizationServerSettings.builder().issuer("https://sso.example").build();
        AuthorizationServerContextHolder.setContext(
                new AuthorizationServerContext() {
                    @Override
                    public String getIssuer() {
                        return settings.getIssuer();
                    }

                    @Override
                    public AuthorizationServerSettings getAuthorizationServerSettings() {
                        return settings;
                    }
                });
        var result =
                new OAuth2AuthorizationConsentAuthenticationToken(
                        "https://sso.example/oauth2/authorize",
                        clientId,
                        principal,
                        state,
                        field == RenderedField.APPROVED_SCOPE ? Set.of(scope) : Set.of(),
                        null);
        result.setAuthenticated(true);
        var filter = new OAuth2AuthorizationEndpointFilter(authentication -> result);
        // Deliberately isolate output encoding from request syntax/client policy validation.
        filter.setAuthenticationConverter(
                request ->
                        new OAuth2AuthorizationCodeRequestAuthenticationToken(
                                "https://sso.example/oauth2/authorize",
                                clientId,
                                principal,
                                "https://client.example/callback",
                                "original-client-state",
                                Set.of(scope),
                                null));
        var request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setServletPath("/oauth2/authorize");
        request.addParameter("response_type", "code");
        request.addParameter("client_id", clientId);
        request.addParameter("redirect_uri", "https://client.example/callback");
        request.addParameter("scope", scope);
        request.addParameter("state", "original-client-state");
        request.setQueryString("response_type=code");
        var response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    throw new AssertionError("Consent must render in the filter");
                });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains("text/html");
        String html = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain(unsafe).contains(HtmlUtils.htmlEscape(unsafe));
    }

    private enum RenderedField {
        CLIENT_ID,
        STATE,
        PRINCIPAL,
        REQUESTED_SCOPE,
        APPROVED_SCOPE
    }
}
