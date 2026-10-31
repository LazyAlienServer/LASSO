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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.admin.AdminClientService;
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
            var csrf = json.readTree(send("GET", "/api/v1/csrf", null, Map.of()).body());
            return send(
                    method,
                    path,
                    body,
                    Map.of(
                            "Content-Type",
                            type,
                            "Origin",
                            ORIGIN,
                            csrf.get("headerName").asText(),
                            csrf.get("token").asText()));
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
