package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.account.PasswordChangeService;
import org.las.sso.oidc.SecurityAwareAuthorizationService;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.session.UserSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OidcPasswordReauthenticationIntegrationTest {

    private static final String PASSWORD = "unique oidc password passphrase";
    private static final String VERIFIER = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGH";
    private static final String CALLBACK = "https://client.example/callback";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired MockMvc mvc;
    @Autowired AccountRegistrationService accounts;
    @Autowired PasswordChangeService passwordChanges;
    @Autowired RegisteredClientRepository clients;
    @Autowired OAuth2AuthorizationService authorizations;
    @Autowired JdbcClient jdbc;
    @Autowired JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Test
    void realIssuedAccessTokenCanReadUserInfoWithoutBrowserCookieAndRevocationStopsIt() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession browser = login(fixture);
        String redirect = mvc.perform(authorize(fixture, null, null).session(browser))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        JsonNode tokens = exchange(fixture, parameter(redirect, "code"));
        String accessToken = tokens.get("access_token").asText();

        var response = mvc.perform(get("/userinfo").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode userInfo = json.readTree(response.getContentAsString());
        assertThat(userInfo.get("sub").asText()).isEqualTo(fixture.subject().toString());
        assertThat(userInfo.get("preferred_username").asText()).isEqualTo(fixture.username());
        assertThat(userInfo.has("email")).isFalse();
        mvc.perform(post("/userinfo").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        mvc.perform(get("/userinfo"))
                .andExpect(status().isUnauthorized()).andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get("/userinfo").accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/userinfo").session(browser))
                .andExpect(status().isUnauthorized());
        var authorization = authorizations.findByToken(accessToken, OAuth2TokenType.ACCESS_TOKEN);
        authorizations.remove(authorization);
        mvc.perform(get("/userinfo").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/userinfo").header("Authorization", "Bearer invalid-userinfo-fixture"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void promptLoginRequiresPasswordAndProducesSignedFrozenEvidence() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession session = login(fixture);
        Instant old = Instant.now().minusSeconds(3600);
        session.setAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE, old);
        String originalSessionId = session.getId();
        String sid = (String) session.getAttribute(UserSessionService.SID_SESSION_ATTRIBUTE);
        String challenge = challenge(fixture, session, "login", null);

        mvc.perform(get("/oauth2/reauthenticate").session(session).param("challenge", challenge))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(session, challenge, "wrong password").andExpect(status().isUnauthorized());
        assertThat(session.getAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE)).isEqualTo(old);
        assertThat(session.getId()).isEqualTo(originalSessionId);

        String returned = verify(session, challenge, PASSWORD).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(session.getId()).isNotEqualTo(originalSessionId);
        assertThat(session.getAttribute(UserSessionService.SID_SESSION_ATTRIBUTE)).isEqualTo(sid);
        Instant checked = (Instant) session.getAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE);
        String location = mvc.perform(get(URI.create(returned)).session(session))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(parameter(location, "state")).isEqualTo(fixture.state());
        String code = parameter(location, "code");
        var stored = authorizations.findByToken(code, new OAuth2TokenType("code"));
        assertThat((Long) stored.getAttribute(SecurityAwareAuthorizationService.PASSWORD_AUTH_TIME_ATTRIBUTE))
                .isEqualTo(checked.getEpochSecond());
        assertThat(jdbc.sql("SELECT last_password_auth_at FROM user_sessions WHERE sid = :sid")
                .param("sid", sid).query(java.time.OffsetDateTime.class).single().toInstant().getEpochSecond())
                .isEqualTo(checked.getEpochSecond());
        JsonNode tokens = exchange(fixture, code);
        Jwt idToken = decoder.decode(tokens.get("id_token").asText());
        assertThat(idToken.getSubject()).isEqualTo(fixture.subject().toString());
        assertThat(idToken.getAudience()).containsExactly(fixture.clientId());
        assertThat(idToken.getClaimAsString("nonce")).isEqualTo(fixture.nonce());
        assertThat(idToken.getClaimAsString("sid")).isEqualTo(sid);
        assertThat(idToken.getClaimAsInstant("auth_time").getEpochSecond()).isEqualTo(checked.getEpochSecond());
        assertThat(idToken.getClaimAsStringList("amr")).containsExactly("pwd");

        // A later password check in this browser cannot upgrade this authorization family.
        session.setAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE, checked.plusSeconds(100));
        JsonNode refreshed = json.readTree(mvc.perform(post("/oauth2/token")
                        .with(httpBasic(fixture.clientId(), fixture.secret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", tokens.get("refresh_token").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(decoder.decode(refreshed.get("id_token").asText())
                .getClaimAsInstant("auth_time")).isEqualTo(idToken.getClaimAsInstant("auth_time"));

        verify(session, challenge, PASSWORD).andExpect(status().isUnauthorized());
        mvc.perform(authorize(fixture, "login", null).session(session))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/oauth2/reauthenticate")));
    }

    @Test
    void maxAgeUsesPasswordTimeAndNoneReturnsLoginRequiredWithoutUi() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession session = login(fixture);
        mvc.perform(authorize(fixture, "none", "60").session(session))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("code=")));
        session.setAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE, Instant.now().minusSeconds(120));
        String denied = mvc.perform(authorize(fixture, "none", "60").session(session))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(parameter(denied, "error")).isEqualTo("login_required");
        assertThat(parameter(denied, "state")).isEqualTo(fixture.state());
        challenge(fixture, session, null, "000");
    }

    @Test
    void missingAndFutureEvidenceCannotSatisfyMaxAge() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession session = login(fixture);
        session.removeAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE);
        challenge(fixture, session, null, "600");
        session.setAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE, Instant.now().plusSeconds(60));
        challenge(fixture, session, null, "600");
    }

    @Test
    void completedChallengeDoesNotBypassPositiveMaxAgeAfterItsPasswordTimeExpires() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession session = login(fixture);
        String challenge = challenge(fixture, session, "login", "1");
        verify(session, challenge, PASSWORD).andExpect(status().is3xxRedirection());
        session.setAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE, Instant.now().minusSeconds(5));
        challenge(fixture, session, "login", "1");
    }

    @Test
    void ordinarySsoDoesNotInventPasswordEvidenceForAnExistingSession() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession session = login(fixture);
        session.removeAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE);
        String location = mvc.perform(authorize(fixture, null, null).session(session))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        Jwt token = decoder.decode(exchange(fixture, parameter(location, "code")).get("id_token").asText());
        assertThat(token.getClaims()).doesNotContainKey("auth_time");
    }

    @Test
    void repeatedFailuresAreRateLimited() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession session = login(fixture);
        String challenge = challenge(fixture, session, "login", "0");
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/oauth2/reauthenticate").session(session).with(csrf())
                            .header("Origin", "http://127.0.0.1:9000")
                            .with(request -> { request.setRemoteAddr("192.0.2.111"); return request; })
                            .param("challenge", challenge).param("password", "incorrect password"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/oauth2/reauthenticate").session(session).with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> { request.setRemoteAddr("192.0.2.111"); return request; })
                        .param("challenge", challenge).param("password", PASSWORD))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void validationRejectsMalformedMaxAgeAndUnsafeRedirectBeforeCreatingChallenge() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession session = login(fixture);
        for (String age : List.of("-1", "abc", "1.5", "9223372036854775808")) {
            mvc.perform(authorize(fixture, "login", age).session(session))
                    .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("error=invalid_request")));
        }
        mvc.perform(get("/oauth2/authorize").queryParam("response_type", "code")
                        .queryParam("client_id", fixture.clientId()).queryParam("scope", "openid")
                        .queryParam("redirect_uri", "https://evil.example/").queryParam("prompt", "login").session(session))
                .andExpect(status().isBadRequest());
        mvc.perform(authorize(fixture, "login", "0").queryParam("max_age", "5").session(session))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("error=invalid_request")));
        assertThat(jdbc.sql("SELECT count(*) FROM oidc_password_challenges WHERE subject = :subject")
                .param("subject", fixture.subject()).query(Long.class).single()).isZero();
    }

    @Test
    void completionIsBoundToRequestAndCannotAuthorizeAnotherState() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession session = login(fixture);
        String challenge = challenge(fixture, session, "login", "0");
        verify(session, challenge, PASSWORD).andExpect(status().is3xxRedirection());
        var changed = new Fixture(fixture.username(), fixture.subject(), fixture.clientId(), fixture.secret(),
                "different-state", fixture.nonce());
        mvc.perform(authorize(changed, "login", "0").session(session))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/oauth2/reauthenticate")));
        verify(session, challenge, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void challengeRequiresOriginalBrowserCsrfSameOriginAndUnexpiredState() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession session = login(fixture);
        String challenge = challenge(fixture, session, "login", "0");
        mvc.perform(post("/oauth2/reauthenticate").session(session)
                        .header("Origin", "http://127.0.0.1:9000")
                        .param("challenge", challenge).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mvc.perform(post("/oauth2/reauthenticate").session(session).with(csrf())
                        .header("Origin", "https://evil.example")
                        .param("challenge", challenge).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        MockHttpSession anotherBrowser = login(fixture);
        verify(anotherBrowser, challenge, PASSWORD).andExpect(status().isUnauthorized());
        jdbc.sql("UPDATE oidc_password_challenges SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = :id")
                .param("id", UUID.fromString(challenge)).update();
        verify(session, challenge, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousForcedLoginIsSatisfiedByOneRealPasswordLogin() throws Exception {
        Fixture fixture = fixture();
        MockHttpSession session = new MockHttpSession();
        mvc.perform(authorize(fixture, "none", "0").session(session))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("error=login_required")));
        mvc.perform(authorize(fixture, "login", "0").session(session))
                .andExpect(header().string("Location", "/login"));
        String returned = mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", fixture.username()).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        mvc.perform(get(URI.create(returned)).session(session))
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(CALLBACK + "?code=")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"login"})
    void revokedBrowserAuthorizationKeepsValidatedRequestThroughFreshPasswordLogin(String prompt) throws Exception {
        Fixture fixture = fixture();
        MockHttpSession old = login(fixture);
        String sid = (String) old.getAttribute(UserSessionService.SID_SESSION_ATTRIBUTE);
        jdbc.sql("UPDATE user_sessions SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP,revoke_reason='FIXTURE_RESET' WHERE sid=:sid")
                .param("sid", sid).update();
        var attempt = mvc.perform(authorize(fixture, prompt, null).session(old))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(old.isInvalid()).isTrue();
        MockHttpSession fresh = (MockHttpSession) attempt.getRequest().getSession(false);
        assertThat(fresh).as("validated pending authorization must belong to a new anonymous session").isNotNull();
        String returned = mvc.perform(post("/login").session(fresh).with(csrf())
                        .param("username", fixture.username()).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(returned).contains("/oauth2/authorize");
        String callback = mvc.perform(get(URI.create(returned)).session(fresh))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(parameter(callback, "state")).isEqualTo(fixture.state());
        Jwt token = decoder.decode(exchange(fixture, parameter(callback, "code")).get("id_token").asText());
        assertThat(token.getClaimAsString("nonce")).isEqualTo(fixture.nonce());
        assertThat(token.getClaimAsString("sid")).isNotEqualTo(sid);
        assertThat(token.getClaimAsInstant("auth_time")).isNotNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"login"})
    void changedCredentialVersionRestartsOidcOnlyAfterNewPasswordVerification(String prompt) throws Exception {
        Fixture fixture = fixture();
        MockHttpSession old = login(fixture);
        String newPassword = "updated unique OIDC password passphrase";
        passwordChanges.changePassword(fixture.subject(), false, PASSWORD, newPassword);
        var attempt = mvc.perform(authorize(fixture, prompt, null).session(old))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(old.isInvalid()).isTrue();
        var fresh = (MockHttpSession) attempt.getRequest().getSession(false);
        assertThat(fresh).isNotNull();
        String isolatedNetwork = "198.51." + (prompt == null ? "10" : "11") + ".1";
        mvc.perform(post("/login").session(fresh).with(csrf())
                        .with(request -> { request.setRemoteAddr(isolatedNetwork); return request; })
                        .param("username", fixture.username()).param("password", PASSWORD))
                .andExpect(header().string("Location", "/login?error"));
        assertThat(authorizationsFor(fixture.subject())).isZero();
        String returned = mvc.perform(post("/login").session(fresh).with(csrf())
                        .with(request -> { request.setRemoteAddr(isolatedNetwork); return request; })
                        .param("username", fixture.username()).param("password", newPassword))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        String callback = mvc.perform(get(URI.create(returned)).session(fresh))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(parameter(callback, "state")).isEqualTo(fixture.state());
        assertThat(decoder.decode(exchange(fixture, parameter(callback, "code")).get("id_token").asText()).getSubject())
                .isEqualTo(fixture.subject().toString());
    }

    @Test
    void revokedBrowserPromptNoneReturnsProtocolLoginRequiredWithoutInventingEvidence() throws Exception {
        Fixture fixture = fixture();
        var old = login(fixture);
        revoke(old);
        var result = mvc.perform(authorize(fixture, "none", "0").session(old))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(old.isInvalid()).isTrue();
        String location = result.getResponse().getRedirectedUrl();
        assertThat(parameter(location, "error")).isEqualTo("login_required");
        assertThat(parameter(location, "state")).isEqualTo(fixture.state());
        assertThat(parameter(location, "code")).isNull();
        assertThat(authorizationsFor(fixture.subject())).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM oidc_password_challenges WHERE subject=:subject")
                .param("subject", fixture.subject()).query(Long.class).single()).isZero();
    }

    @Test
    void revokedBrowserCannotCacheUnvalidatedRedirectOrReplayItsOldPasswordChallenge() throws Exception {
        Fixture fixture = fixture();
        var old = login(fixture);
        String oldChallenge = challenge(fixture, old, "login", "0");
        revoke(old);
        mvc.perform(authorize(fixture, "login", "0")
                        .with(request -> { request.setParameter("redirect_uri", "https://evil.example/callback"); return request; })
                        .session(old))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(old.isInvalid()).isTrue();
        var newTransaction = new MockHttpSession();
        mvc.perform(authorize(fixture, "login", "0").session(newTransaction)).andExpect(status().is3xxRedirection());
        String returned = mvc.perform(post("/login").session(newTransaction).with(csrf())
                        .param("username", fixture.username()).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(returned).doesNotContain("evil.example");
        verify(newTransaction, oldChallenge, PASSWORD).andExpect(status().isUnauthorized());
        String callback = mvc.perform(get(URI.create(returned)).session(newTransaction))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(parameter(callback, "state")).isEqualTo(fixture.state());
        assertThat(parameter(callback, "code")).isNotBlank();
    }

    @Test
    void revokedBrowserStillRejectsMalformedMaxAgeBeforeSavingANewPasswordTransaction() throws Exception {
        Fixture fixture = fixture();
        var old = login(fixture);
        revoke(old);
        var response = mvc.perform(authorize(fixture, "login", "invalid").session(old))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        assertThat(parameter(response.getRedirectedUrl(), "error")).isEqualTo("invalid_request");
        assertThat(old.isInvalid()).isTrue();
        assertThat(authorizationsFor(fixture.subject())).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM oidc_password_challenges WHERE subject=:subject")
                .param("subject", fixture.subject()).query(Long.class).single()).isZero();
    }

    private void revoke(MockHttpSession old) {
        jdbc.sql("UPDATE user_sessions SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP,revoke_reason='FIXTURE_RESET' WHERE sid=:sid")
                .param("sid", (String) old.getAttribute(UserSessionService.SID_SESSION_ATTRIBUTE)).update();
    }

    private long authorizationsFor(UUID subject) {
        return jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE principal_name=:subject")
                .param("subject", subject.toString()).query(Long.class).single();
    }

    private Fixture fixture() {
        String username = "Oidc" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var account = accounts.register(username, "OIDC test user", PASSWORD);
        String clientId = "reauth-" + UUID.randomUUID();
        String secret = UUID.randomUUID().toString();
        var client = RegisteredClient.withId(UUID.randomUUID().toString()).clientId(clientId)
                .clientSecret(passwordEncoder.encode(secret)).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN).redirectUri(CALLBACK)
                .scope("openid").scope("profile")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().reuseRefreshTokens(false).build()).build();
        clients.save(client);
        jdbc.sql("""
                INSERT INTO registered_client_metadata
                    (registered_client_id, client_type, status, config_version, resource_audiences, require_pkce, updated_at)
                VALUES (:id, 'FIRST_PARTY', 'ACTIVE', 1, ARRAY['urn:las:test']::text[], true, CURRENT_TIMESTAMP)
                """).param("id", client.getId()).update();
        return new Fixture(username, account.userId(), clientId, secret, UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    private MockHttpSession login(Fixture fixture) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", fixture.username()).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    private MockHttpServletRequestBuilder authorize(Fixture fixture, String prompt, String maxAge) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        var request = get("/oauth2/authorize").queryParam("response_type", "code").queryParam("client_id", fixture.clientId())
                .queryParam("redirect_uri", CALLBACK).queryParam("scope", "openid profile").queryParam("state", fixture.state())
                .queryParam("nonce", fixture.nonce()).queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256");
        if (prompt != null) request.queryParam("prompt", prompt);
        if (maxAge != null) request.queryParam("max_age", maxAge);
        return request;
    }

    private String challenge(Fixture fixture, MockHttpSession session, String prompt, String maxAge) throws Exception {
        String location = mvc.perform(authorize(fixture, prompt, maxAge).session(session))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/oauth2/reauthenticate?challenge=");
        return parameter(location, "challenge");
    }

    private org.springframework.test.web.servlet.ResultActions verify(MockHttpSession session, String challenge, String password)
            throws Exception {
        return mvc.perform(post("/oauth2/reauthenticate").session(session).with(csrf())
                .header("Origin", "http://127.0.0.1:9000")
                .param("challenge", challenge).param("password", password));
    }

    private JsonNode exchange(Fixture fixture, String code) throws Exception {
        return json.readTree(mvc.perform(post("/oauth2/token").with(httpBasic(fixture.clientId(), fixture.secret()))
                        .param("grant_type", "authorization_code").param("code", code)
                        .param("redirect_uri", CALLBACK).param("code_verifier", VERIFIER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String parameter(String uri, String name) {
        return UriComponentsBuilder.fromUriString(uri).build().getQueryParams().getFirst(name);
    }

    private record Fixture(String username, UUID subject, String clientId, String secret, String state, String nonce) { }
}
