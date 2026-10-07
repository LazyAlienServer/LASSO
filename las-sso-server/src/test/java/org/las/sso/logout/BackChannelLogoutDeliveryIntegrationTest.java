package org.las.sso.logout;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual outbox claim/state updates, signature issuer and HTTP delivery against an isolated
 * PostgreSQL fixture.
 */
@SpringBootTest(
        properties = {"las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class BackChannelLogoutDeliveryIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired JdbcClient jdbc;
    @Autowired BackChannelLogoutDelivery delivery;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (UUID id : eventIds)
            jdbc.sql("DELETE FROM outbox_events WHERE id = :id").param("id", id).update();
    }

    @Test
    void redirectIsNotDeliverySuccessAndDoesNotForwardTheLogoutToken() throws Exception {
        try (var endpoint = new Endpoint(302)) {
            UUID id = enqueue(endpoint.uri(), 0);

            delivery.deliverPending();

            var state = state(id);
            assertThat(state.processed()).isFalse();
            assertThat(state.attempts()).isEqualTo(1);
            assertThat(state.claimed()).isFalse();
            assertThat(state.dead()).isFalse();
            assertThat(state.availableAt()).isAfter(state.createdAt());
            assertThat(state.error()).isEqualTo("HTTP_302");
            assertThat(endpoint.requests).hasValue(1);
            assertThat(endpoint.redirectRequests).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204})
    void onlyDocumentedAcknowledgementsCompleteDelivery(int status) throws Exception {
        try (var endpoint = new Endpoint(status)) {
            UUID id = enqueue(endpoint.uri(), 0);
            double before =
                    meters.get("las.sso.backchannel.delivery")
                            .tag("result", "success")
                            .counter()
                            .count();

            delivery.deliverPending();
            delivery.deliverPending();

            var state = state(id);
            assertThat(state.processed()).isTrue();
            assertThat(state.attempts()).isZero();
            assertThat(state.dead()).isFalse();
            assertThat(state.claimed()).isFalse();
            assertThat(state.error()).isNull();
            assertThat(endpoint.requests).hasValue(1);
            assertThat(
                            meters.get("las.sso.backchannel.delivery")
                                    .tag("result", "success")
                                    .counter()
                                    .count())
                    .isEqualTo(before + 1);
            assertThat(endpoint.bodies)
                    .allSatisfy(body -> assertThat(body).startsWith("logout_token="));
            assertThat(endpoint.methods).containsExactly("POST");
            assertThat(endpoint.redirectRequests).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 202, 206, 301, 303, 307, 308, 400, 401, 429, 500, 503})
    void unacknowledgedResponsesRemainUnprocessedAndRetryable(int status) throws Exception {
        try (var endpoint = new Endpoint(status)) {
            UUID id = enqueue(endpoint.uri(), 0);

            delivery.deliverPending();

            var state = state(id);
            assertThat(state.processed()).isFalse();
            assertThat(state.dead()).isFalse();
            assertThat(state.claimed()).isFalse();
            assertThat(state.attempts()).isEqualTo(1);
            assertThat(state.error()).isEqualTo("HTTP_" + status);
            assertThat(endpoint.requests).hasValue(1);
            assertThat(endpoint.redirectRequests).hasValue(0);
        }
    }

    @Test
    void unsuccessfulResponseCanBeAcknowledgedOnALaterAttemptWithoutSleeping() throws Exception {
        try (var endpoint = new Endpoint(302)) {
            UUID id = enqueue(endpoint.uri(), 0);
            delivery.deliverPending();
            assertThat(state(id).processed()).isFalse();
            assertThat(state(id).attempts()).isEqualTo(1);
            endpoint.status = 204;
            // Make the recorded retry due; do not wait out random production backoff in a DB-state
            // test.
            makeDue(id);

            delivery.deliverPending();

            assertThat(state(id).processed()).isTrue();
            assertThat(state(id).attempts()).isEqualTo(1);
            assertThat(state(id).error()).isNull();
            assertThat(state(id).claimed()).isFalse();
            assertThat(endpoint.requests).hasValue(2);
            assertThat(endpoint.redirectRequests).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 500})
    void eightRejectedResponsesDeadLetterWithoutLeakingResponseOrToken(
            int status, CapturedOutput output) throws Exception {
        try (var endpoint = new Endpoint(status)) {
            UUID id = enqueue(endpoint.uri(), 0);
            double retryBefore =
                    meters.get("las.sso.backchannel.delivery")
                            .tag("result", "retry")
                            .counter()
                            .count();
            double deadBefore =
                    meters.get("las.sso.backchannel.delivery")
                            .tag("result", "dead")
                            .counter()
                            .count();
            for (int attempt = 1; attempt <= 8; attempt++) {
                makeDue(id);
                delivery.deliverPending();
                assertThat(state(id).attempts()).isEqualTo(attempt);
                assertThat(state(id).processed()).isFalse();
                assertThat(state(id).claimed()).isFalse();
                assertThat(state(id).dead()).isEqualTo(attempt == 8);
            }

            makeDue(id);
            delivery.deliverPending();

            assertThat(endpoint.requests).hasValue(8);
            assertThat(
                            meters.get("las.sso.backchannel.delivery")
                                    .tag("result", "retry")
                                    .counter()
                                    .count())
                    .isEqualTo(retryBefore + 7);
            assertThat(
                            meters.get("las.sso.backchannel.delivery")
                                    .tag("result", "dead")
                                    .counter()
                                    .count())
                    .isEqualTo(deadBefore + 1);
            assertThat(state(id).error()).isEqualTo("HTTP_" + status);
            assertThat(endpoint.redirectRequests).hasValue(0);
            assertThat(output.getAll())
                    .contains("errorCode=HTTP_" + status)
                    .doesNotContain("response-body-secret-canary");
            for (String body : endpoint.bodies) {
                String token =
                        java.net.URLDecoder.decode(
                                body.substring("logout_token=".length()), StandardCharsets.UTF_8);
                assertThat(output.getAll()).doesNotContain(token);
            }
        }
    }

    @Test
    void connectionClosedWithoutResponseRetainsRetryableOutboxState() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            endpoint.dropConnection = true;
            UUID id = enqueue(endpoint.uri(), 0);

            delivery.deliverPending();

            assertThat(state(id).processed()).isFalse();
            assertThat(state(id).attempts()).isEqualTo(1);
            assertThat(state(id).dead()).isFalse();
            assertThat(state(id).claimed()).isFalse();
            assertThat(state(id).error()).isEqualTo("ResourceAccessException");
        }
    }

    @Test
    void rejectedReceiverDoesNotPreventAnotherReceiverFromCompleting() throws Exception {
        try (var rejected = new Endpoint(302);
                var accepted = new Endpoint(204)) {
            UUID rejectedId = enqueue(rejected.uri(), 0);
            UUID acceptedId = enqueue(accepted.uri(), 0);

            delivery.deliverPending();

            assertThat(state(rejectedId).processed()).isFalse();
            assertThat(state(rejectedId).attempts()).isEqualTo(1);
            assertThat(state(acceptedId).processed()).isTrue();
            assertThat(rejected.requests).hasValue(1);
            assertThat(accepted.requests).hasValue(1);
        }
    }

    private void makeDue(UUID id) {
        jdbc.sql(
                        "UPDATE outbox_events SET available_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = :id")
                .param("id", id)
                .update();
    }

    private UUID enqueue(String uri, int attempts) {
        UUID id = UUID.randomUUID();
        eventIds.add(id);
        String subject = "019fb42f-5f16-7c31-8ce0-9e54f7441ff0";
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        jdbc.sql(
                        """
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload_json,
                     created_at, available_at, attempt_count)
                VALUES (:id, 'USER', :subject, 'BACKCHANNEL_LOGOUT_REQUESTED',
                        jsonb_build_object('userId', :subject, 'clientId', 'b3-delivery-client',
                                           'uri', :uri, 'sid', 'b3-delivery-sid'),
                        :now, :now, :attempts)
                """)
                .param("id", id)
                .param("subject", subject)
                .param("uri", uri)
                .param("now", now)
                .param("attempts", attempts)
                .update();
        return id;
    }

    private State state(UUID id) {
        return jdbc.sql(
                        """
                SELECT processed_at IS NOT NULL AS processed, dead_lettered_at IS NOT NULL AS dead,
                       claimed_at IS NOT NULL OR claim_token IS NOT NULL AS claimed,
                       attempt_count, created_at, available_at, last_error_code
                  FROM outbox_events WHERE id = :id
                """)
                .param("id", id)
                .query(
                        (rs, row) ->
                                new State(
                                        rs.getBoolean("processed"),
                                        rs.getBoolean("dead"),
                                        rs.getBoolean("claimed"),
                                        rs.getInt("attempt_count"),
                                        rs.getObject("created_at", OffsetDateTime.class),
                                        rs.getObject("available_at", OffsetDateTime.class),
                                        rs.getString("last_error_code")))
                .single();
    }

    private record State(
            boolean processed,
            boolean dead,
            boolean claimed,
            int attempts,
            OffsetDateTime createdAt,
            OffsetDateTime availableAt,
            String error) {}

    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger redirectRequests = new AtomicInteger();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> methods = new CopyOnWriteArrayList<>();
        volatile int status;
        volatile boolean dropConnection;

        Endpoint(int status) throws IOException {
            this.status = status;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/backchannel",
                    exchange -> {
                        bodies.add(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        methods.add(exchange.getRequestMethod());
                        requests.incrementAndGet();
                        if (dropConnection) {
                            exchange.close();
                            return;
                        }
                        exchange.getResponseHeaders()
                                .set("Location", uri().replace("/backchannel", "/login"));
                        byte[] body =
                                "response-body-secret-canary".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(
                                this.status, this.status == 204 ? -1 : body.length);
                        if (this.status != 204) exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.createContext(
                    "/login",
                    exchange -> {
                        exchange.getRequestBody().readAllBytes();
                        redirectRequests.incrementAndGet();
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                    });
            server.start();
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/backchannel";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
