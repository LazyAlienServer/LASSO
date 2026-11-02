package org.las.sso.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.altcha.altcha.v2.Altcha;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.admin.AdminClientService;
import org.las.sso.security.AltchaChallengeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real Servlet/security/MVC/error dispatch against an isolated PostgreSQL database. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "las.sso.scheduling.enabled=false",
            "spring.session.jdbc.cleanup-cron=-"
        })
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
@Import(ApiBoundaryIntegrationTest.ErrorProbeConfiguration.class)
class ApiBoundaryIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired Environment environment;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired AccountRegistrationService accounts;
    @Autowired AdminClientService clients;
    @Autowired RegisteredClientRepository registrations;
    @Autowired PasswordEncoder encoder;

    private static final String ORIGIN = "http://127.0.0.1:9000";
    private static final String PASSWORD = "boundary fixture password phrase";

    @BeforeEach
    void resetDisposableBudgets() {
        jdbc.sql("DELETE FROM rate_limit_buckets").update();
    }

    protected String contextPath() {
        return "";
    }

    @Test
    void anonymousApiIsProblemWhileBrowserAndOidcKeepTheirProtocols() throws Exception {
        Browser browser = new Browser();
        problem(
                browser.send("GET", "/api/v1/me/profile", null, Map.of("Accept", "text/html")),
                401,
                "AUTHENTICATION_REQUIRED");
        var page = browser.send("GET", "/account", null, Map.of());
        assertThat(page.statusCode()).isEqualTo(302);
        requestId(page);
        var userinfo = browser.send("GET", "/userinfo", null, Map.of());
        assertThat(userinfo.statusCode()).isEqualTo(401);
        assertThat(userinfo.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(userinfo.body()).isEmpty();
        requestId(userinfo);
    }

    @Test
    void machineEndpointsNeverRedirectToBrowserLoginRegardlessOfAccept() throws Exception {
        Browser browser = new Browser();
        for (String accept : List.of("", "*/*", "text/html", "application/json")) {
            for (String path : List.of("/oauth2/token", "/oauth2/introspect", "/oauth2/revoke")) {
                var headers = new LinkedHashMap<String, String>();
                headers.put("Content-Type", "application/x-www-form-urlencoded");
                if (!accept.isEmpty()) headers.put("Accept", accept);
                var response =
                        browser.send(
                                "POST", path, "token=fixture&grant_type=refresh_token", headers);
                assertThat(response.statusCode()).as("%s Accept=%s", path, accept).isEqualTo(401);
                assertThat(response.headers().firstValue("Location")).isEmpty();
                assertThat(json.readTree(response.body()).get("error").asText())
                        .isEqualTo("invalid_client");
                assertThat(response.body()).doesNotContain("AUTHENTICATION_REQUIRED", "<html");
                requestId(response);
            }
        }
    }

    @Test
    void duplicateFieldsAndUnpairedJsonSurrogatesCannotChangeProfileOrPassword() throws Exception {
        Browser browser = new Browser();
        UUID id = browser.login(false);
        String before = browser.send("GET", "/api/v1/me/profile", null, Map.of()).body();
        for (String body :
                List.of(
                        "{\"displayName\":\"first\",\"displayName\":\"last\"}",
                        "{\"displayName\":\"first\",\"display\\u004eame\":\"last\"}",
                        "{\"displayName\":\"\\uD800\"}",
                        "{\"displayName\":\"\\uDC00\"}",
                        "{\"displayName\":\"\\uD800x\"}",
                        "{\"displayName\":\"\\uDC00\\uD800\"}")) {
            problem(
                    browser.write("PATCH", "/api/v1/me/profile", body, "application/json"),
                    400,
                    "VALIDATION_FAILED");
            assertThat(browser.send("GET", "/api/v1/me/profile", null, Map.of()).body())
                    .isEqualTo(before);
        }
        String hashBefore =
                jdbc.sql("SELECT password_hash FROM password_credentials WHERE user_id=:id")
                        .param("id", id)
                        .query(String.class)
                        .single();
        problem(
                browser.write(
                        "POST",
                        "/api/v1/me/password-change",
                        "{\"currentPassword\":\""
                                + PASSWORD
                                + "\",\"newPassword\":\"valid phrase \\uD800\"}",
                        "application/json"),
                400,
                "VALIDATION_FAILED");
        assertThat(
                        jdbc.sql("SELECT password_hash FROM password_credentials WHERE user_id=:id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo(hashBefore);
        var valid =
                browser.write(
                        "PATCH",
                        "/api/v1/me/profile",
                        "{\"displayName\":\"中文\\uD83D\\uDE00\"}",
                        "application/json");
        assertThat(valid.statusCode()).isEqualTo(200);
        assertThat(json.readTree(valid.body()).get("displayName").asText()).isEqualTo("中文😀");
    }

    @ParameterizedTest
    @ValueSource(ints = {33, 64, 65})
    void registrationAndProfileUseTheSameDisplayNameCodePointLimit(int count) throws Exception {
        Browser browser = new Browser();
        String displayName = "😀".repeat(count);
        var issued =
                browser.write(
                        "POST",
                        "/api/v1/captcha/challenges",
                        "{\"purpose\":\"REGISTER\"}",
                        "application/json");
        assertThat(issued.statusCode()).isEqualTo(201);
        var challenge = json.readValue(issued.body(), AltchaChallengeService.IssuedChallenge.class);
        var altcha = new Altcha.Challenge(challenge.parameters(), challenge.signature());
        String payload =
                Base64.getEncoder()
                        .encodeToString(
                                json.writeValueAsBytes(
                                        new Altcha.Payload(
                                                altcha,
                                                Altcha.solveChallenge(
                                                        altcha, Altcha.kdf("PBKDF2/SHA-256")))));
        String username = "Points" + UUID.randomUUID().toString().substring(0, 8);
        var result =
                browser.write(
                        "POST",
                        "/api/v1/registrations",
                        json.writeValueAsString(
                                Map.of(
                                        "username",
                                        username,
                                        "displayName",
                                        displayName,
                                        "password",
                                        PASSWORD,
                                        "captcha",
                                        Map.of(
                                                "challengeId",
                                                challenge.challengeId(),
                                                "payload",
                                                payload))),
                        "application/json",
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        if (count <= 64) {
            assertThat(result.statusCode()).isEqualTo(201);
            assertThat(json.readTree(result.body()).get("user").get("displayName").asText())
                    .isEqualTo(displayName);
        } else {
            problem(result, 400, "VALIDATION_FAILED");
        }
        browser.login(false);
        var updated =
                browser.write(
                        "PATCH",
                        "/api/v1/me/profile",
                        json.writeValueAsString(Map.of("displayName", displayName)),
                        "application/json");
        if (count <= 64) {
            assertThat(updated.statusCode()).isEqualTo(200);
            assertThat(json.readTree(updated.body()).get("displayName").asText())
                    .isEqualTo(displayName);
        } else {
            problem(updated, 400, "VALIDATION_FAILED");
        }
    }

    @Test
    void csrfAndRoleFailuresHaveTheSameProblemContract() throws Exception {
        Browser browser = new Browser();
        problem(
                browser.send(
                        "POST",
                        "/api/v1/captcha/challenges",
                        "{}",
                        Map.of("Content-Type", "application/json")),
                403,
                "CSRF_INVALID");
        browser.login(false);
        problem(browser.send("GET", "/api/v1/admin/clients", null, Map.of()), 403, "ACCESS_DENIED");
    }

    @Test
    void mvcAndContainerErrorsPreserveStatusesAndSafeProblemBodies() throws Exception {
        Browser browser = new Browser();
        browser.login(false);
        problem(
                browser.send("GET", "/api/v1/no-such-route", null, Map.of()),
                404,
                "RESOURCE_NOT_FOUND");
        var wrongMethod = browser.write("PUT", "/api/v1/me/profile", "{}", "application/json");
        problem(wrongMethod, 405, "METHOD_NOT_ALLOWED");
        assertThat(wrongMethod.headers().firstValue("Allow").orElseThrow())
                .contains("GET", "PATCH");
        problem(
                browser.write("PATCH", "/api/v1/me/profile", "{}", "text/plain"),
                415,
                "UNSUPPORTED_MEDIA_TYPE");
        problem(
                browser.write("PATCH", "/api/v1/me/profile", "{", "application/json"),
                400,
                "VALIDATION_FAILED");
        problem(
                browser.write(
                        "PATCH",
                        "/api/v1/me/profile",
                        "{\"displayName\":\"test\",\"unknown\":1}",
                        "application/json"),
                400,
                "VALIDATION_FAILED");
        problem(
                browser.write("POST", "/api/v1/me/logout-all", "{}", "application/json"),
                400,
                "VALIDATION_FAILED");
        problem(
                browser.write(
                        "DELETE", "/api/v1/me/sessions/no-such-sid", null, "application/json"),
                404,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void unexpectedFailuresAndServletErrorDispatchDoNotLeakPrivateDetails() throws Exception {
        Browser browser = new Browser();
        browser.login(false);
        for (String path : List.of("/api/v1/__boundary/throw", "/api/v1/__boundary/send-error")) {
            var response = browser.send("GET", path, null, Map.of("Accept", "text/html"));
            problem(response, 500, "INTERNAL_SERVER_ERROR");
            assertThat(response.body())
                    .doesNotContain(
                            "boundary-private-detail", "IllegalStateException", "stackTrace");
        }
    }

    @Test
    void malformedQueryAndPathTypesRemainClientErrors() throws Exception {
        Browser browser = new Browser();
        browser.login(true);
        for (String path :
                List.of(
                        "/api/v1/admin/clients?limit=abc",
                        "/api/v1/admin/users/not-a-uuid",
                        "/api/v1/admin/users?cursor=not-a-uuid")) {
            problem(browser.send("GET", path, null, Map.of()), 400, "VALIDATION_FAILED");
        }
    }

    @Test
    void sqlMetacharactersRemainBoundDataInAdminFilters() throws Exception {
        Browser browser = new Browser();
        browser.login(true);
        for (String payload :
                List.of(
                        "' OR '1'='1' --",
                        "'; SELECT 1; --",
                        "' UNION SELECT NULL --",
                        "absent'" + UUID.randomUUID())) {
            String value = URLEncoder.encode(payload, StandardCharsets.UTF_8);
            for (String path :
                    List.of(
                            "/api/v1/admin/users?query=",
                            "/api/v1/admin/clients?status=ALL&query=",
                            "/api/v1/admin/audit-events?action=",
                            "/api/v1/admin/audit-events?targetId=",
                            "/api/v1/admin/security-events?type=",
                            "/api/v1/admin/security-events?severity=")) {
                var response = browser.send("GET", path + value, null, Map.of());
                assertThat(response.statusCode()).as("%s", path).isEqualTo(200);
                assertThat(json.readTree(response.body()).get("items").isEmpty())
                        .as("%s", path)
                        .isTrue();
            }
        }
        String payload = URLEncoder.encode("' OR 1=1 --", StandardCharsets.UTF_8);
        String cursor =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                ("2026-01-01T00:00:00Z|' OR 1=1 --")
                                        .getBytes(StandardCharsets.UTF_8));
        for (String path :
                List.of(
                        "/api/v1/admin/users?status=" + payload,
                        "/api/v1/admin/clients?status=" + payload,
                        "/api/v1/admin/deletion-requests?status=" + payload,
                        "/api/v1/admin/users?cursor=" + payload,
                        "/api/v1/admin/users?limit=" + payload,
                        "/api/v1/admin/users/" + payload,
                        "/api/v1/admin/security-events?userId=" + payload,
                        "/api/v1/admin/audit-events?from=" + payload,
                        "/api/v1/admin/audit-events?to=" + payload,
                        "/api/v1/admin/audit-events?cursor=" + cursor,
                        "/api/v1/admin/clients?cursor=" + cursor,
                        "/api/v1/admin/deletion-requests?cursor=" + cursor)) {
            var response = browser.send("GET", path, null, Map.of());
            problem(response, 400, "VALIDATION_FAILED");
            assertThat(response.body())
                    .doesNotContain("SELECT", "SQLException", "syntax error", "jdbc");
        }
        problem(
                browser.send("GET", "/api/v1/admin/clients/" + payload, null, Map.of()),
                404,
                "CLIENT_NOT_FOUND");
    }

    @Test
    void sqlLookingTextRoundTripsWithoutExecutingOrChangingOtherRows() throws Exception {
        Browser browser = new Browser();
        UUID actor = browser.login(true);
        UUID other =
                accounts.register(
                                "Other" + UUID.randomUUID().toString().substring(0, 8),
                                "Unchanged",
                                PASSWORD)
                        .userId();
        String payload = "O'Reilly'); SELECT 1; --";
        var updated =
                browser.write(
                        "PATCH",
                        "/api/v1/me/profile",
                        json.writeValueAsString(Map.of("displayName", payload)),
                        "application/json");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(
                        jdbc.sql("SELECT display_name FROM users WHERE id=:id")
                                .param("id", actor)
                                .query(String.class)
                                .single())
                .isEqualTo(payload);
        assertThat(
                        jdbc.sql("SELECT display_name FROM users WHERE id=:id")
                                .param("id", other)
                                .query(String.class)
                                .single())
                .isEqualTo("Unchanged");
        String id = "bound-sql-" + UUID.randomUUID();
        var body =
                Map.of(
                        "clientId",
                        id,
                        "clientName",
                        payload,
                        "clientType",
                        "FIRST_PARTY",
                        "redirectUris",
                        List.of("https://boundary.test/callback"),
                        "postLogoutRedirectUris",
                        List.of(),
                        "scopes",
                        List.of("openid", "profile"),
                        "resourceAudiences",
                        List.of(payload),
                        "reason",
                        payload);
        var created =
                browser.write(
                        "POST",
                        "/api/v1/admin/clients",
                        json.writeValueAsString(body),
                        "application/json");
        assertThat(created.statusCode()).isEqualTo(200);
        var detail = browser.send("GET", "/api/v1/admin/clients/" + id, null, Map.of());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(json.readTree(detail.body()).get("clientName").asText()).isEqualTo(payload);
        assertThat(json.readTree(detail.body()).get("resourceAudiences").get(0).asText())
                .isEqualTo(payload);
        String value = URLEncoder.encode(payload, StandardCharsets.UTF_8);
        var searched =
                json.readTree(
                                browser.send(
                                                "GET",
                                                "/api/v1/admin/clients?status=ALL&query=" + value,
                                                null,
                                                Map.of())
                                        .body())
                        .get("items");
        assertThat(searched.size()).isEqualTo(1);
        assertThat(searched.get(0).get("clientId").asText()).isEqualTo(id);
        assertThat(
                        jdbc.sql(
                                        "SELECT reason FROM admin_audit_events WHERE target_id=:id AND action='ADMIN_CLIENT_CREATE'")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo(payload);
        long before =
                jdbc.sql("SELECT count(*) FROM oauth2_registered_client")
                        .query(Long.class)
                        .single();
        var invalid = new LinkedHashMap<String, Object>(body);
        invalid.put("clientId", "' OR 1=1 --");
        problem(
                browser.write(
                        "POST",
                        "/api/v1/admin/clients",
                        json.writeValueAsString(invalid),
                        "application/json"),
                400,
                "INVALID_CLIENT_ID");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM oauth2_registered_client")
                                .query(Long.class)
                                .single())
                .isEqualTo(before);
    }

    @Test
    void sqlLookingLoginAndClientCredentialsCannotAuthenticate() throws Exception {
        Browser browser = new Browser();
        var csrf = json.readTree(browser.send("GET", "/api/v1/csrf", null, Map.of()).body());
        var response =
                browser.send(
                        "POST",
                        "/login",
                        "username="
                                + URLEncoder.encode("' OR 1=1 --", StandardCharsets.UTF_8)
                                + "&password="
                                + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)
                                + "&"
                                + csrf.get("parameterName").asText()
                                + "="
                                + URLEncoder.encode(
                                        csrf.get("token").asText(), StandardCharsets.UTF_8),
                        Map.of(
                                "Content-Type",
                                "application/x-www-form-urlencoded",
                                "Origin",
                                ORIGIN));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow())
                .contains("/login?error");
        problem(
                browser.send("GET", "/api/v1/me/profile", null, Map.of()),
                401,
                "AUTHENTICATION_REQUIRED");
        String basic =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (URLEncoder.encode("' OR 1=1 --", StandardCharsets.UTF_8)
                                                        + ":fixture")
                                                .getBytes(StandardCharsets.US_ASCII));
        var token =
                browser.send(
                        "POST",
                        "/oauth2/token",
                        "grant_type=refresh_token&refresh_token=fixture",
                        Map.of(
                                "Content-Type",
                                "application/x-www-form-urlencoded",
                                "Authorization",
                                basic));
        assertThat(token.statusCode()).isIn(400, 401);
        assertThat(json.readTree(token.body()).get("error").asText()).isEqualTo("invalid_client");
        assertThat(token.body()).doesNotContain("SQLException", "syntax error", "SELECT");
        String rawBasic =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        "' OR 1=1 --:fixture".getBytes(StandardCharsets.UTF_8));
        problem(
                browser.send(
                        "GET",
                        "/internal/v1/principals/" + UUID.randomUUID() + "/service-access",
                        null,
                        Map.of("Authorization", rawBasic)),
                401,
                "CLIENT_AUTHENTICATION_REQUIRED");
    }

    @Test
    void authenticatedProtocolTokenLookupsTreatSqlTextAsAnUnknownToken() throws Exception {
        Browser browser = new Browser();
        UUID actor = browser.login(true);
        String id = "sql-protocol-" + UUID.randomUUID();
        var client =
                clients.create(
                        new AdminClientService.CreateRequest(
                                id,
                                "SQL fixture",
                                "FIRST_PARTY",
                                List.of("https://boundary.test/callback"),
                                List.of(),
                                List.of("openid", "profile"),
                                List.of("urn:las:sql-fixture"),
                                null),
                        actor,
                        null,
                        "fixture",
                        UUID.randomUUID());
        String basic =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (id + ":" + client.clientSecret())
                                                .getBytes(StandardCharsets.UTF_8));
        var headers =
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Authorization", basic);
        long before =
                jdbc.sql("SELECT count(*) FROM oauth2_authorization").query(Long.class).single();
        for (String payload : List.of("' OR 1=1 --", "'; SELECT 1; --")) {
            String value = URLEncoder.encode(payload, StandardCharsets.UTF_8);
            var introspected =
                    browser.send("POST", "/oauth2/introspect", "token=" + value, headers);
            assertThat(introspected.statusCode()).isEqualTo(200);
            assertThat(json.readTree(introspected.body()).get("active").asBoolean()).isFalse();
            assertThat(
                            browser.send("POST", "/oauth2/revoke", "token=" + value, headers)
                                    .statusCode())
                    .isEqualTo(200);
            var refreshed =
                    browser.send(
                            "POST",
                            "/oauth2/token",
                            "grant_type=refresh_token&refresh_token=" + value,
                            headers);
            assertThat(refreshed.statusCode()).isEqualTo(400);
            assertThat(json.readTree(refreshed.body()).get("error").asText())
                    .isEqualTo("invalid_grant");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM oauth2_authorization").query(Long.class).single())
                .isEqualTo(before);
    }

    @Test
    void passwordErrorsUseProblemWithoutEchoingAnyPassword() throws Exception {
        Browser browser = new Browser();
        browser.login(false);
        var result =
                browser.write(
                        "POST",
                        "/api/v1/me/password-change",
                        json.writeValueAsString(
                                Map.of(
                                        "currentPassword",
                                        "wrong-boundary-password",
                                        "newPassword",
                                        "new-boundary-password-2026")),
                        "application/json");
        problem(result, 401, "CURRENT_PASSWORD_INVALID");
        assertThat(result.body())
                .doesNotContain("wrong-boundary-password", "new-boundary-password-2026");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "true", "12.3"})
    void wrongJsonScalarTypesCannotMutateProfile(String scalar) throws Exception {
        Browser browser = new Browser();
        browser.login(false);
        var before = browser.send("GET", "/api/v1/me/profile", null, Map.of()).body();
        problem(
                browser.write(
                        "PATCH",
                        "/api/v1/me/profile",
                        "{\"displayName\":" + scalar + "}",
                        "application/json"),
                400,
                "VALIDATION_FAILED");
        assertThat(browser.send("GET", "/api/v1/me/profile", null, Map.of()).body())
                .isEqualTo(before);
    }

    @Test
    void invalidUtf8CannotChangeProfileAndChineseRoundTrips() throws Exception {
        Browser browser = new Browser();
        browser.login(false);
        String document = "{\"displayName\":\"编码验证😀\"}";
        var updated = browser.write("PATCH", "/api/v1/me/profile", document, "application/json");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).get("displayName").asText()).isEqualTo("编码验证😀");
        var csrf = json.readTree(browser.send("GET", "/api/v1/csrf", null, Map.of()).body());
        Map<String, String> headers =
                Map.of(
                        "Content-Type",
                        "application/json",
                        "Origin",
                        ORIGIN,
                        csrf.get("headerName").asText(),
                        csrf.get("token").asText());
        byte[] invalid = "{\"displayName\":\"badX\"}".getBytes(StandardCharsets.UTF_8);
        invalid[invalid.length - 3] = (byte) 0xff;
        problem(
                browser.bytes("PATCH", "/api/v1/me/profile", invalid, headers),
                400,
                "VALIDATION_FAILED");
        problem(
                browser.bytes(
                        "PATCH",
                        "/api/v1/me/profile",
                        document.getBytes(StandardCharsets.UTF_16),
                        headers),
                400,
                "VALIDATION_FAILED");
        problem(
                browser.write(
                        "PATCH",
                        "/api/v1/me/profile",
                        document,
                        "application/json;charset=ISO-8859-1"),
                400,
                "VALIDATION_FAILED");
        assertThat(
                        json.readTree(
                                        browser.send("GET", "/api/v1/me/profile", null, Map.of())
                                                .body())
                                .get("displayName")
                                .asText())
                .isEqualTo("编码验证😀");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"redirectUris", "postLogoutRedirectUris", "scopes", "resourceAudiences"})
    void nullClientListElementsCannotCreateOrChangeClients(String field) throws Exception {
        Browser browser = new Browser();
        UUID actor = browser.login(true);
        String id = "boundary-" + UUID.randomUUID();
        var created =
                clients.create(
                        new AdminClientService.CreateRequest(
                                id,
                                "Boundary",
                                "FIRST_PARTY",
                                List.of("https://boundary.test/callback"),
                                List.of(),
                                List.of("openid", "profile"),
                                List.of("urn:las:boundary"),
                                null),
                        actor,
                        null,
                        "fixture",
                        UUID.randomUUID());
        var before = clients.detail(id);
        var body = new LinkedHashMap<String, Object>();
        body.put("clientName", "Boundary changed");
        body.put("clientType", "FIRST_PARTY");
        body.put("redirectUris", List.of("https://boundary.test/callback"));
        body.put("postLogoutRedirectUris", List.of());
        body.put("scopes", List.of("openid", "profile"));
        body.put("resourceAudiences", List.of("urn:las:boundary"));
        body.put("reason", "invalid input fixture");
        body.put(field, java.util.Collections.singletonList(null));
        problem(
                browser.write(
                        "PATCH",
                        "/api/v1/admin/clients/" + id,
                        json.writeValueAsString(body),
                        "application/json"),
                400,
                "VALIDATION_FAILED");
        assertThat(clients.detail(id)).isEqualTo(before);
        body.put("clientId", id + "-new");
        problem(
                browser.write(
                        "POST",
                        "/api/v1/admin/clients",
                        json.writeValueAsString(body),
                        "application/json"),
                400,
                "VALIDATION_FAILED");
        assertThat(registrations.findByClientId(id + "-new")).isNull();
        assertThat(
                        encoder.matches(
                                created.clientSecret(),
                                registrations.findByClientId(id).getClientSecret()))
                .isTrue();
    }

    @Test
    void basicCredentialsUseStrictUtf8AndDeclareItWithoutChangingTheDecision() throws Exception {
        String id = "utf8-" + UUID.randomUUID();
        RegisteredClient client =
                RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId(id)
                        .clientSecret(encoder.encode("秘密-clé-password"))
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .build();
        registrations.save(client);
        jdbc.sql(
                        "INSERT INTO registered_client_metadata(registered_client_id,client_type,status,config_version,resource_audiences,require_pkce,updated_at) VALUES (:id,'FIRST_PARTY','ACTIVE',1,ARRAY['urn:las:boundary'],true,CURRENT_TIMESTAMP)")
                .param("id", client.getId())
                .update();
        Browser browser = new Browser();
        String path = "/internal/v1/principals/" + UUID.randomUUID() + "/service-access";
        String basic =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (id + ":秘密-clé-password").getBytes(StandardCharsets.UTF_8));
        var allowed = browser.send("GET", path, null, Map.of("Authorization", basic));
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(allowed.body()).get("reason").asText())
                .isEqualTo("PRINCIPAL_NOT_FOUND");
        var missing = browser.send("GET", path, null, Map.of());
        problem(missing, 401, "CLIENT_AUTHENTICATION_REQUIRED");
        assertThat(missing.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("charset=\"UTF-8\"");
        registrations.save(
                RegisteredClient.from(client)
                        .clientSecret(encoder.encode("prefix\uFFFDsuffix"))
                        .build());
        byte[] prefix = (id + ":prefix").getBytes(StandardCharsets.UTF_8);
        byte[] malformed = java.util.Arrays.copyOf(prefix, prefix.length + 7);
        malformed[prefix.length] = (byte) 0xff;
        System.arraycopy(
                "suffix".getBytes(StandardCharsets.UTF_8), 0, malformed, prefix.length + 1, 6);
        problem(
                browser.send(
                        "GET",
                        path,
                        null,
                        Map.of(
                                "Authorization",
                                "Basic " + Base64.getEncoder().encodeToString(malformed))),
                401,
                "CLIENT_AUTHENTICATION_REQUIRED");
    }

    @Test
    void discoveryAdvertisesOnlyTheSupportedApplicationProfile() throws Exception {
        Browser browser = new Browser();
        for (String path :
                List.of(
                        "/.well-known/openid-configuration",
                        "/.well-known/oauth-authorization-server")) {
            var response = browser.send("GET", path, null, Map.of());
            assertThat(response.statusCode()).isEqualTo(200);
            var data = json.readTree(response.body());
            assertThat(data.get("grant_types_supported").toString())
                    .contains("authorization_code", "refresh_token")
                    .doesNotContain("client_credentials", "token-exchange");
            assertThat(data.get("scopes_supported").toString()).contains("openid", "profile");
            if (path.contains("openid-configuration")) {
                assertThat(data.get("backchannel_logout_supported").asBoolean()).isTrue();
                assertThat(data.get("backchannel_logout_session_supported").asBoolean()).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/oauth2/jwks",
                "/.well-known/openid-configuration",
                "/.well-known/oauth-authorization-server"
            })
    void protocolJsonAdvertisesUtf8BeforeSecurityFiltersWrite(String path) throws Exception {
        var response =
                new Browser()
                        .send(
                                "GET",
                                path,
                                null,
                                Map.of(
                                        "Accept",
                                        "application/json",
                                        "Accept-Charset",
                                        "ISO-8859-1"));
        assertThat(response.statusCode()).isEqualTo(200);
        var mediaType =
                org.springframework.http.MediaType.parseMediaType(
                        response.headers().firstValue("Content-Type").orElseThrow());
        assertThat(mediaType.isCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .isTrue();
        assertThat(mediaType.getCharset()).isIn(null, StandardCharsets.UTF_8);
        var data = json.readTree(response.body());
        if (path.equals("/oauth2/jwks")) {
            assertThat(data.get("keys").isArray()).isTrue();
            assertThat(data.get("keys").isEmpty()).isFalse();
            for (JsonNode key : data.get("keys")) {
                for (String privateField : List.of("d", "p", "q", "dp", "dq", "qi", "k")) {
                    assertThat(key.has(privateField)).isFalse();
                }
            }
        }
    }

    private void problem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                .contains("no-store");
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("requestId").asText()).isEqualTo(requestId(response));
        assertThat(body.has("timestamp")).isFalse();
    }

    private String requestId(HttpResponse<?> response) {
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        return id;
    }

    private final class Browser {
        private final HttpClient http =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();

        HttpResponse<String> send(
                String method, String path, String body, Map<String, String> headers)
                throws Exception {
            return bytes(
                    method,
                    path,
                    body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                    headers);
        }

        HttpResponse<String> bytes(
                String method, String path, byte[] body, Map<String, String> headers)
                throws Exception {
            var builder =
                    HttpRequest.newBuilder(
                            URI.create(
                                    "http://127.0.0.1:"
                                            + environment.getRequiredProperty("local.server.port")
                                            + contextPath()
                                            + path));
            headers.forEach(builder::header);
            return http.send(
                    builder.method(
                                    method,
                                    body == null
                                            ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpResponse<String> write(String method, String path, String body, String type)
                throws Exception {
            return write(method, path, body, type, Map.of());
        }

        HttpResponse<String> write(
                String method,
                String path,
                String body,
                String type,
                Map<String, String> extraHeaders)
                throws Exception {
            var csrf = json.readTree(send("GET", "/api/v1/csrf", null, Map.of()).body());
            var headers = new LinkedHashMap<String, String>(extraHeaders);
            headers.put("Content-Type", type);
            headers.put("Origin", ORIGIN);
            headers.put(csrf.get("headerName").asText(), csrf.get("token").asText());
            return send(method, path, body, headers);
        }

        UUID login(boolean admin) throws Exception {
            String username = "Boundary" + UUID.randomUUID().toString().substring(0, 8);
            var account = accounts.register(username, "Boundary Display", PASSWORD);
            if (admin) {
                jdbc.sql("UPDATE users SET is_sso_admin=false WHERE is_sso_admin=true").update();
                jdbc.sql("UPDATE users SET is_sso_admin=true WHERE id=:id")
                        .param("id", account.userId())
                        .update();
            }
            var csrf = json.readTree(send("GET", "/api/v1/csrf", null, Map.of()).body());
            String form =
                    "username="
                            + username
                            + "&password="
                            + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)
                            + "&"
                            + csrf.get("parameterName").asText()
                            + "="
                            + URLEncoder.encode(csrf.get("token").asText(), StandardCharsets.UTF_8);
            assertThat(
                            send(
                                            "POST",
                                            "/login",
                                            form,
                                            Map.of(
                                                    "Content-Type",
                                                    "application/x-www-form-urlencoded",
                                                    "Origin",
                                                    ORIGIN))
                                    .statusCode())
                    .isEqualTo(302);
            if (admin)
                assertThat(
                                write(
                                                "POST",
                                                "/api/v1/me/reauthentication",
                                                json.writeValueAsString(
                                                        Map.of("password", PASSWORD)),
                                                "application/json")
                                        .statusCode())
                        .isEqualTo(204);
            return account.userId();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ErrorProbeConfiguration {
        @Bean
        ErrorProbe errorProbe() {
            return new ErrorProbe();
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class ErrorProbe {
        @org.springframework.web.bind.annotation.GetMapping("/api/v1/__boundary/throw")
        void unexpected() {
            throw new IllegalStateException("boundary-private-detail");
        }

        @org.springframework.web.bind.annotation.GetMapping("/api/v1/__boundary/send-error")
        void sendError(jakarta.servlet.http.HttpServletResponse response)
                throws java.io.IOException {
            response.sendError(500, "boundary-private-detail");
        }
    }
}
