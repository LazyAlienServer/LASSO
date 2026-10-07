package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.admin.AdminClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Small workstation transport/hash/DB load observation, not a production SLA or saturation
 * benchmark.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1", "server.forward-headers-strategy=none",
            "las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-"
        })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServiceAccessQueryLoadIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService accounts;
    @Autowired AdminClientService clients;
    @Autowired Environment environment;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @Test
    @Timeout(90)
    void validQueriesAboveFailureBudgetTotalsCompleteWithoutAccumulatingFailures()
            throws Exception {
        var account =
                accounts.register(
                        "Load" + UUID.randomUUID().toString().substring(0, 8),
                        "Load fixture",
                        "load fixture owner password phrase");
        var client =
                clients.create(
                        new AdminClientService.CreateRequest(
                                "load-" + UUID.randomUUID(),
                                "Load fixture",
                                "FIRST_PARTY",
                                List.of("https://load.example/callback"),
                                List.of(),
                                List.of("openid"),
                                List.of("urn:las:load"),
                                null),
                        account.userId(),
                        null,
                        "fixture",
                        UUID.randomUUID());
        var http =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + environment.getRequiredProperty(
                                                        "local.server.port")
                                                + "/internal/v1/principals/"
                                                + account.userId()
                                                + "/service-access"))
                        .header(
                                "Authorization",
                                "Basic "
                                        + Base64.getEncoder()
                                                .encodeToString(
                                                        (client.clientId()
                                                                        + ":"
                                                                        + client.clientSecret())
                                                                .getBytes(
                                                                        StandardCharsets
                                                                                .ISO_8859_1)))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();
        for (int warmup = 0; warmup < 4; warmup++)
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);
        int workers = 8;
        int perWorker = 10;
        var start = new CountDownLatch(1);
        var timings = new ArrayList<Long>();
        long beginning = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<List<Long>>>();
            for (int worker = 0; worker < workers; worker++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                                    var local = new ArrayList<Long>();
                                    for (int query = 0; query < perWorker; query++) {
                                        long before = System.nanoTime();
                                        var response =
                                                http.send(
                                                        request,
                                                        HttpResponse.BodyHandlers.ofString());
                                        local.add(System.nanoTime() - before);
                                        assertThat(response.statusCode()).isEqualTo(200);
                                        assertThat(response.headers().firstValue("Cache-Control"))
                                                .contains("no-store");
                                        var body = json.readTree(response.body());
                                        assertThat(body.get("decision").asText())
                                                .isEqualTo("ALLOWED");
                                        assertThat(body.get("stateVersion").asLong()).isOne();
                                        assertThat(body.get("stateEpoch").asText()).isNotBlank();
                                    }
                                    return local;
                                }));
            }
            start.countDown();
            for (var future : futures) timings.addAll(future.get(60, TimeUnit.SECONDS));
        }
        long elapsed = System.nanoTime() - beginning;
        timings.sort(Long::compareTo);
        assertThat(timings).hasSize(workers * perWorker);
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM rate_limit_buckets WHERE scope IN ('FIRST_PARTY_AUTH_CLIENT','FIRST_PARTY_AUTH_NETWORK')")
                                .query(Long.class)
                                .single())
                .isZero();
        System.out.printf(
                java.util.Locale.ROOT,
                "SERVICE_ACCESS_LOAD_OBSERVATION requests=%d workers=%d elapsedMs=%.1f rps=%.2f p50Ms=%.1f p95Ms=%.1f p99Ms=%.1f%n",
                timings.size(),
                workers,
                elapsed / 1_000_000.0,
                timings.size() * 1_000_000_000.0 / elapsed,
                timings.get((timings.size() - 1) / 2) / 1_000_000.0,
                timings.get((int) Math.ceil(timings.size() * 0.95) - 1) / 1_000_000.0,
                timings.get((int) Math.ceil(timings.size() * 0.99) - 1) / 1_000_000.0);
    }
}
