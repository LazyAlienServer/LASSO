package org.las.sso.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.las.sso.account.AccountRegistrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Real dual-port HTTP, authentication, meters and PostgreSQL; no mocked scrape/security chain. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1", "server.forward-headers-strategy=none",
            "las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-",
            "las.sso.monitoring.enabled=true", "las.sso.monitoring.snapshot-interval=PT5M",
            "management.server.address=127.0.0.1",
                    "management.endpoints.web.exposure.include=health,prometheus"
        })
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class MonitoringIntegrationTest {

    private static final String SECRET = "collector-private-fixture-phrase";
    private static final int MANAGEMENT_PORT = availablePort();

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @DynamicPropertySource
    static void monitoring(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> MANAGEMENT_PORT);
        registry.add(
                "las.sso.monitoring.password-hash",
                () -> "{bcrypt}" + new BCryptPasswordEncoder(10).encode(SECRET));
    }

    @Autowired Environment environment;
    @Autowired MeterRegistry meters;
    @Autowired MonitoringSnapshot snapshot;
    @Autowired JdbcClient jdbc;
    @Autowired AccountRegistrationService accounts;
    @Autowired ObjectMapper json;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void dedicatedCredentialScrapesWithoutCookieAndDoesNotExposeSecretLabels() throws Exception {
        var response =
                get(MANAGEMENT_PORT, "/actuator/prometheus", basic("las-sso-metrics", SECRET));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.body())
                .contains("las_sso_login_attempts_total", "las_sso_snapshot_available")
                .doesNotContain(
                        SECRET,
                        "collector-private",
                        "client_id=",
                        "username=",
                        "subject=",
                        "sid=",
                        "request_id=");
    }

    @Test
    void anonymousWrongAndOidcCredentialsCannotScrape() throws Exception {
        assertThat(get(MANAGEMENT_PORT, "/actuator/prometheus", null).statusCode()).isEqualTo(401);
        assertThat(
                        get(
                                        MANAGEMENT_PORT,
                                        "/actuator/prometheus",
                                        basic("las-sso-metrics", "wrong"))
                                .statusCode())
                .isEqualTo(401);
        assertThat(
                        get(
                                        MANAGEMENT_PORT,
                                        "/actuator/prometheus",
                                        basic("laslog-bff", "laslog-local-only"))
                                .statusCode())
                .isEqualTo(401);
    }

    @Test
    void scrapeCredentialCannotUseApplicationPortOrBusinessRoutes() throws Exception {
        String credential = basic("las-sso-metrics", SECRET);
        assertThat(get(appPort(), "/actuator/prometheus", credential).statusCode()).isEqualTo(403);
        assertThat(
                        get(
                                        appPort(),
                                        "/internal/v1/principals/"
                                                + UUID.randomUUID()
                                                + "/service-access",
                                        credential)
                                .statusCode())
                .isEqualTo(401);
        assertThat(get(appPort(), "/api/v1/me/profile", credential).statusCode()).isNotEqualTo(200);
        var post =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + MANAGEMENT_PORT
                                                        + "/actuator/prometheus"))
                                .header("Authorization", credential)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(403);
    }

    @Test
    void browserLoginCookieDoesNotAuthenticateScrape() throws Exception {
        String username = "Meter" + UUID.randomUUID().toString().substring(0, 8);
        String password = "metered account private fixture phrase";
        accounts.register(username, "Meter fixture", password);
        var browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var csrfResponse =
                browser.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:" + appPort() + "/api/v1/csrf"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var csrf = json.readTree(csrfResponse.body());
        var login =
                browser.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + appPort() + "/login"))
                                .header("Origin", "http://127.0.0.1:9000")
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "username="
                                                        + username
                                                        + "&password="
                                                        + URLEncoder.encode(
                                                                password, StandardCharsets.UTF_8)
                                                        + "&"
                                                        + csrf.get("parameterName").asText()
                                                        + "="
                                                        + URLEncoder.encode(
                                                                csrf.get("token").asText(),
                                                                StandardCharsets.UTF_8)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(302);
        var denied =
                browser.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + MANAGEMENT_PORT
                                                        + "/actuator/prometheus"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(
                        meters.get("las.sso.login.attempts")
                                .tags("result", "success", "reason", "none")
                                .counter()
                                .count())
                .isGreaterThan(0);
        assertThat(
                        meters.get("las.sso.password.hash")
                                .tags(
                                        "operation",
                                        "match",
                                        "algorithm",
                                        "argon2id",
                                        "result",
                                        "success")
                                .timer()
                                .count())
                .isGreaterThan(0);
    }

    @Test
    void internalAuthenticationResultsHaveFixedTagsAndNoIdentity() throws Exception {
        double success =
                meters.get("las.sso.internal.authentication")
                        .tag("result", "success")
                        .counter()
                        .count();
        double invalid =
                meters.get("las.sso.internal.authentication")
                        .tag("result", "invalid_credentials")
                        .counter()
                        .count();
        String path = "/internal/v1/principals/" + UUID.randomUUID() + "/service-access";
        assertThat(get(appPort(), path, basic("laslog-bff", "laslog-local-only")).statusCode())
                .isEqualTo(200);
        assertThat(get(appPort(), path, basic("laslog-bff", "wrong")).statusCode()).isEqualTo(401);
        assertThat(
                        meters.get("las.sso.internal.authentication")
                                .tag("result", "success")
                                .counter()
                                .count())
                .isEqualTo(success + 1);
        assertThat(
                        meters.get("las.sso.internal.authentication")
                                .tag("result", "invalid_credentials")
                                .counter()
                                .count())
                .isEqualTo(invalid + 1);
        assertThat(
                        get(
                                        MANAGEMENT_PORT,
                                        "/actuator/prometheus",
                                        basic("las-sso-metrics", SECRET))
                                .body())
                .doesNotContain(path);
    }

    @Test
    void queueSnapshotIncludesDeadAndExpiredClaimsAndTracksRecovery() {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO outbox_events(id, aggregate_type, aggregate_id, event_type, payload_json,
                    created_at, available_at, claimed_at, claim_token)
                VALUES (:id, 'USER', :id, 'GLOBAL_LOGOUT_REQUESTED', '{}',
                    CURRENT_TIMESTAMP - INTERVAL '6 minutes', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP - INTERVAL '3 minutes', :id)
                """)
                .param("id", id)
                .update();
        try {
            snapshot.refresh();
            assertThat(meters.get("las.sso.outbox.pending").gauge().value())
                    .isGreaterThanOrEqualTo(1);
            assertThat(meters.get("las.sso.outbox.expired.claims").gauge().value())
                    .isGreaterThanOrEqualTo(1);
            assertThat(meters.get("las.sso.outbox.oldest.age").gauge().value()).isGreaterThan(300);
            jdbc.sql("UPDATE outbox_events SET dead_lettered_at=CURRENT_TIMESTAMP WHERE id=:id")
                    .param("id", id)
                    .update();
            snapshot.refresh();
            assertThat(meters.get("las.sso.outbox.dead").gauge().value()).isGreaterThanOrEqualTo(1);
            assertThat(meters.get("las.sso.snapshot.available").gauge().value()).isEqualTo(1);
        } finally {
            jdbc.sql("DELETE FROM outbox_events WHERE id=:id").param("id", id).update();
            snapshot.refresh();
        }
        assertThat(meters.get("las.sso.outbox.pending").gauge().value()).isZero();
        assertThat(meters.get("las.sso.outbox.dead").gauge().value()).isZero();
    }

    @Test
    void failedSnapshotKeepsLastValuesButMarksThemUnavailableUntilSuccessfulRefresh() {
        snapshot.refresh();
        double success = meters.get("las.sso.snapshot.last.success").gauge().value();
        double failures = meters.get("las.sso.snapshot.failures").counter().count();
        double active = meters.get("las.sso.sessions.active").gauge().value();
        jdbc.sql("ALTER TABLE outbox_events RENAME TO monitoring_fixture_outbox").update();
        try {
            snapshot.refresh();
            assertThat(meters.get("las.sso.snapshot.available").gauge().value()).isZero();
            assertThat(meters.get("las.sso.snapshot.last.success").gauge().value())
                    .isEqualTo(success);
            assertThat(meters.get("las.sso.sessions.active").gauge().value()).isEqualTo(active);
            assertThat(meters.get("las.sso.snapshot.failures").counter().count())
                    .isEqualTo(failures + 1);
        } finally {
            jdbc.sql("ALTER TABLE monitoring_fixture_outbox RENAME TO outbox_events").update();
        }
        snapshot.refresh();
        assertThat(meters.get("las.sso.snapshot.available").gauge().value()).isEqualTo(1);
    }

    private HttpResponse<String> get(int port, String path, String authorization) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET();
        if (authorization != null) request.header("Authorization", authorization);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int appPort() {
        return environment.getRequiredProperty("local.server.port", Integer.class);
    }

    private static String basic(String username, String secret) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static int availablePort() {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Monitoring fixture port unavailable", exception);
        }
    }
}
