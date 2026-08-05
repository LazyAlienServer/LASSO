package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.util.UriComponentsBuilder;

class LasSsoGlobalLogoutControllerTest {

    @Test
    void globalLogoutClearsLocalIdentityAndBuildsStateBoundRpLogout() {
        var controller = controller();
        var authentication = authentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var request = new MockHttpServletRequest("POST", "/api/auth/logout/global");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8088);
        var oldSession = (org.springframework.mock.web.MockHttpSession) request.getSession(true);
        var response = new MockHttpServletResponse();

        var result = controller.logoutGlobal(request, response, authentication);

        assertThat(result.getStatusCode().value()).isEqualTo(303);
        assertThat(oldSession.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        var parameters = UriComponentsBuilder.fromUri(result.getHeaders().getLocation())
                .build().getQueryParams();
        assertThat(parameters.getFirst("id_token_hint")).isEqualTo("server-side-id-token");
        assertThat(parameters.getFirst("post_logout_redirect_uri"))
                .isEqualTo("http://127.0.0.1:8088/logged-out");
        assertThat(parameters.getFirst("state")).hasSizeGreaterThanOrEqualTo(43);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");

        var pendingSession = (org.springframework.mock.web.MockHttpSession) request.getSession(false);
        var callback = new MockHttpServletRequest("GET", "/logged-out");
        callback.setSession(pendingSession);
        var completed = controller.loggedOut(parameters.getFirst("state"), callback);

        assertThat(completed.getStatusCode().value()).isEqualTo(303);
        assertThat(completed.getHeaders().getLocation().toString()).isEqualTo("/");
        assertThat(pendingSession.isInvalid()).isTrue();
    }

    @Test
    void logoutReturnRejectsWrongOrReplayedState() {
        var controller = controller();
        var request = new MockHttpServletRequest("POST", "/api/auth/logout/global");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8088);
        var response = new MockHttpServletResponse();
        var started = controller.logoutGlobal(request, response, authentication());
        String state = UriComponentsBuilder.fromUri(started.getHeaders().getLocation())
                .build().getQueryParams().getFirst("state");
        var pendingSession = request.getSession(false);
        var callback = new MockHttpServletRequest("GET", "/logged-out");
        callback.setSession(pendingSession);

        assertThat(controller.loggedOut("wrong-state", callback).getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.loggedOut(state, callback).getStatusCode().value())
                .isEqualTo(303);

        var replay = new MockHttpServletRequest("GET", "/logged-out");
        assertThat(controller.loggedOut(state, replay).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void anonymousGlobalLogoutDoesNotCreateAStateSession() {
        var request = new MockHttpServletRequest("POST", "/api/auth/logout/global");

        var result = controller().logoutGlobal(
                request, new MockHttpServletResponse(), null);

        assertThat(result.getStatusCode().value()).isEqualTo(401);
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void propertiesRejectAnExternalLocalSuccessRedirect() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LasSsoClientProperties(
                        "las-sso", Duration.ofSeconds(60),
                        "{baseUrl}/logged-out", "https://attacker.example", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LasSsoGlobalLogoutController controller() {
        return new LasSsoGlobalLogoutController(
                new InMemoryClientRegistrationRepository(registration()),
                new LasSsoClientProperties("las-sso", Duration.ofSeconds(60)));
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("las-sso")
                .clientId("laslog-bff")
                .clientSecret("secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:8088/login/oauth2/code/las-sso")
                .scope("openid", "profile")
                .authorizationUri("http://127.0.0.1:9000/oauth2/authorize")
                .tokenUri("http://127.0.0.1:9000/oauth2/token")
                .jwkSetUri("http://127.0.0.1:9000/oauth2/jwks")
                .issuerUri("http://127.0.0.1:9000")
                .userInfoUri("http://127.0.0.1:9000/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .providerConfigurationMetadata(Map.of(
                        "end_session_endpoint", "http://127.0.0.1:9000/connect/logout"))
                .clientName("LAS SSO")
                .build();
    }

    private static OAuth2AuthenticationToken authentication() {
        Instant now = Instant.now();
        var idToken = new OidcIdToken("server-side-id-token", now, now.plusSeconds(600), Map.of(
                IdTokenClaimNames.ISS, "http://127.0.0.1:9000",
                IdTokenClaimNames.SUB, "019fc000-0000-7000-8000-000000000001",
                IdTokenClaimNames.AUD, List.of("laslog-bff")));
        var user = new DefaultOidcUser(List.of(), idToken);
        return new OAuth2AuthenticationToken(user, List.of(), "las-sso");
    }
}
