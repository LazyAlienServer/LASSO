package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.las.sso.account.AccountRegistrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Actual configured security/provider/JDBC/default-consent flow; Mock Servlet login is not browser
 * execution.
 */
@SpringBootTest(
        properties =
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthorizationConsentIntegrationTest {

    private static final String PASSWORD = "d1 consent owner password phrase";
    private static final String VERIFIER = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGH";
    private static final String CALLBACK = "https://partner.example/callback";

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService accounts;
    @Autowired RegisteredClientRepository clients;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtDecoder decoder;

    @Test
    void partnerConsentApprovalPersistsConsentAndExchangesCodeWithOriginalStateNonceAndPkce()
            throws Exception {
        var fixture = fixture(true);
        var browser = login(fixture);
        String html = consent(fixture, browser);
        String consentState = hidden(html, "state");
        assertThat(consentState).isNotEqualTo(fixture.state());

        String redirect =
                mvc.perform(
                                post("/oauth2/authorize")
                                        .session(browser)
                                        .param("client_id", fixture.clientId())
                                        .param("state", consentState)
                                        .param("scope", "profile"))
                        .andExpect(status().is3xxRedirection())
                        .andReturn()
                        .getResponse()
                        .getRedirectedUrl();

        assertThat(parameter(redirect, "state")).isEqualTo(fixture.state());
        var tokens =
                json.readTree(
                        mvc.perform(
                                        post("/oauth2/token")
                                                .with(
                                                        httpBasic(
                                                                fixture.clientId(),
                                                                fixture.secret()))
                                                .param("grant_type", "authorization_code")
                                                .param("code", parameter(redirect, "code"))
                                                .param("redirect_uri", CALLBACK)
                                                .param("code_verifier", VERIFIER))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        var id = decoder.decode(tokens.get("id_token").asText());
        assertThat(id.getSubject()).isEqualTo(fixture.userId().toString());
        assertThat(id.getAudience()).containsExactly(fixture.clientId());
        assertThat(id.getClaimAsString("nonce")).isEqualTo(fixture.nonce());
        assertThat(tokens.get("refresh_token").asText()).isNotBlank();
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM oauth2_authorization_consent WHERE registered_client_id = :id AND principal_name = :user")
                                .param("id", fixture.registeredId())
                                .param("user", fixture.userId().toString())
                                .query(Long.class)
                                .single())
                .isOne();
        mvc.perform(authorize(fixture).session(browser)).andExpect(status().is3xxRedirection());
    }

    @Test
    void partnerConsentCancellationReturnsAccessDeniedAndDoesNotPersistApproval() throws Exception {
        var fixture = fixture(true);
        var browser = login(fixture);
        String state = hidden(consent(fixture, browser), "state");

        String redirect =
                mvc.perform(
                                post("/oauth2/authorize")
                                        .session(browser)
                                        .param("client_id", fixture.clientId())
                                        .param("state", state))
                        .andExpect(status().is3xxRedirection())
                        .andReturn()
                        .getResponse()
                        .getRedirectedUrl();

        assertThat(parameter(redirect, "error")).isEqualTo("access_denied");
        assertThat(parameter(redirect, "state")).isEqualTo(fixture.state());
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM oauth2_authorization_consent WHERE registered_client_id = :id")
                                .param("id", fixture.registeredId())
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void anotherSubjectCannotApproveThePendingAuthorization() throws Exception {
        var fixture = fixture(true);
        var initiating = login(fixture);
        var other = login(fixture(true));
        String state = hidden(consent(fixture, initiating), "state");

        mvc.perform(
                        post("/oauth2/authorize")
                                .session(other)
                                .param("client_id", fixture.clientId())
                                .param("state", state)
                                .param("scope", "profile"))
                .andExpect(status().isBadRequest());
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM oauth2_authorization_consent WHERE registered_client_id = :id")
                                .param("id", fixture.registeredId())
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void firstPartyAuthorizationStillSkipsConsentUnderExistingPolicy() throws Exception {
        var fixture = fixture(false);
        var browser = login(fixture);

        String redirect =
                mvc.perform(authorize(fixture).session(browser))
                        .andExpect(status().is3xxRedirection())
                        .andReturn()
                        .getResponse()
                        .getRedirectedUrl();

        assertThat(parameter(redirect, "code")).isNotBlank();
        assertThat(parameter(redirect, "state")).isEqualTo(fixture.state());
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM oauth2_authorization_consent WHERE registered_client_id = :id")
                                .param("id", fixture.registeredId())
                                .query(Long.class)
                                .single())
                .isZero();
    }

    private Fixture fixture(boolean partner) {
        String username = "D1Consent" + UUID.randomUUID().toString().substring(0, 8);
        var account = accounts.register(username, "D1 fixture", PASSWORD);
        String clientId = "d1-consent-" + UUID.randomUUID();
        String secret = UUID.randomUUID().toString();
        var client =
                RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId(clientId)
                        .clientSecret(encoder.encode(secret))
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                        .redirectUri(CALLBACK)
                        .scope("openid")
                        .scope("profile")
                        .clientSettings(
                                ClientSettings.builder()
                                        .requireProofKey(true)
                                        .requireAuthorizationConsent(partner)
                                        .build())
                        .tokenSettings(TokenSettings.builder().reuseRefreshTokens(false).build())
                        .build();
        clients.save(client);
        jdbc.sql(
                        """
                INSERT INTO registered_client_metadata
                    (registered_client_id, client_type, status, config_version, resource_audiences, require_pkce, updated_at)
                VALUES (:id, CAST(:type AS registered_client_type), 'ACTIVE', 1, ARRAY['urn:las:d1']::text[], true, CURRENT_TIMESTAMP)
                """)
                .param("id", client.getId())
                .param("type", partner ? "REVIEWED_PARTNER" : "FIRST_PARTY")
                .update();
        return new Fixture(
                account.userId(),
                username,
                client.getId(),
                clientId,
                secret,
                "client-original-\"><img src=x onerror=alert('d1')>&",
                UUID.randomUUID().toString());
    }

    private MockHttpSession login(Fixture fixture) throws Exception {
        var session = new MockHttpSession();
        mvc.perform(
                        post("/login")
                                .session(session)
                                .with(csrf())
                                .param("username", fixture.username())
                                .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    private String consent(Fixture fixture, MockHttpSession browser) throws Exception {
        String html =
                mvc.perform(authorize(fixture).session(browser))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html)
                .contains(
                        "Consent required", "name=\"scope\" value=\"profile\"", fixture.clientId())
                .doesNotContain(fixture.state(), "<img src=x");
        return html;
    }

    private MockHttpServletRequestBuilder authorize(Fixture fixture) throws Exception {
        String challenge =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        return get("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", fixture.clientId())
                .queryParam("redirect_uri", CALLBACK)
                .queryParam("scope", "openid profile")
                .queryParam("state", fixture.state())
                .queryParam("nonce", fixture.nonce())
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256");
    }

    private static String hidden(String html, String name) {
        var match = Pattern.compile("name=\"" + name + "\" value=\"([^\"]+)\"").matcher(html);
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    private static String parameter(String redirect, String name) {
        String raw =
                UriComponentsBuilder.fromUriString(redirect)
                        .build()
                        .getQueryParams()
                        .getFirst(name);
        return raw == null ? null : UriUtils.decode(raw, StandardCharsets.UTF_8);
    }

    private record Fixture(
            UUID userId,
            String username,
            String registeredId,
            String clientId,
            String secret,
            String state,
            String nonce) {}
}
