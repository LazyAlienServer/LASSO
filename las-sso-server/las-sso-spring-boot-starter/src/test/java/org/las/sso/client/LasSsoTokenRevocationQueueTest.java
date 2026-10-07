package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

@Timeout(15)
@ExtendWith(OutputCaptureExtension.class)
class LasSsoTokenRevocationQueueTest {

    @Test
    void redirectIsRetriedAgainstTheOriginalEndpointInsteadOfAcceptedOrFollowed() throws Exception {
        var calls = new AtomicInteger();
        var redirects = new AtomicInteger();
        var retried = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext(
                "/revoke",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    int call = calls.incrementAndGet();
                    exchange.getResponseHeaders().set("Location", base + "/login");
                    exchange.sendResponseHeaders(call == 1 ? 302 : 200, -1);
                    exchange.close();
                    if (call == 2) retried.countDown();
                });
        server.createContext(
                "/login",
                exchange -> {
                    redirects.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        try (var queue = new LasSsoTokenRevocationQueue()) {
            queue.submit(
                    registration(base + "/revoke"), "test-only-refresh-token", "refresh_token");

            // First production retry is bounded by two seconds; no mock response or accelerated
            // timer here.
            assertThat(retried.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(2);
            assertThat(redirects).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exact200AcknowledgementStopsRetryingAndSendsClientAuthenticationAndTokenForm()
            throws Exception {
        try (var endpoint = new Endpoint(200);
                var timer = new ControlledScheduler();
                var queue = new LasSsoTokenRevocationQueue(timer)) {
            queue.enqueue(registration(endpoint.uri()), "revocation-token-canary", "refresh_token");

            timer.runAll();

            assertThat(endpoint.calls).hasValue(1);
            assertThat(timer.retryDelays).isEmpty();
            assertThat(endpoint.methods).containsExactly("POST");
            assertThat(endpoint.auth)
                    .containsExactly(
                            "Basic "
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    "b3-test-client:b3-test-secret"
                                                            .getBytes(StandardCharsets.UTF_8)));
            assertThat(endpoint.bodies)
                    .containsExactly("token=revocation-token-canary&token_type_hint=refresh_token");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 202, 204, 206, 301, 302, 303, 307, 308, 400, 401, 429, 500, 503})
    void otherStatusesUseTheExistingRetryScheduleThenStopAtEightAttempts(
            int status, CapturedOutput output) throws Exception {
        try (var endpoint = new Endpoint(status);
                var timer = new ControlledScheduler();
                var queue = new LasSsoTokenRevocationQueue(timer)) {
            queue.submit(registration(endpoint.uri()), "revocation-token-canary", "refresh_token");

            timer.runAll();

            assertThat(endpoint.calls).hasValue(8);
            assertThat(endpoint.redirects).hasValue(0);
            assertThat(timer.retryDelays).hasSize(7);
            for (int index = 0; index < 7; index++) {
                assertThat(timer.retryDelays.get(index))
                        .isBetween(1L, Math.min(300L, 1L << (index + 1)));
            }
            assertThat(output.getAll())
                    .contains("errorCode=HTTP_" + status)
                    .doesNotContain(
                            "response-body-secret-canary",
                            "revocation-token-canary",
                            "b3-test-secret");
        }
    }

    @Test
    void later200StopsTheRetryChainAfterAnUnacknowledgedResponse() throws Exception {
        try (var endpoint = new Endpoint(307);
                var timer = new ControlledScheduler();
                var queue = new LasSsoTokenRevocationQueue(timer)) {
            queue.submit(registration(endpoint.uri()), "revocation-token-canary", "refresh_token");
            timer.runNext();
            assertThat(endpoint.calls).hasValue(1);
            assertThat(timer.retryDelays).hasSize(1);
            endpoint.status = 200;

            timer.runAll();

            assertThat(endpoint.calls).hasValue(2);
            assertThat(timer.retryDelays).hasSize(1);
            assertThat(endpoint.redirects).hasValue(0);
        }
    }

    @Test
    void missingHttpResponseRetriesEightTimesWithoutLoggingTokenOrTransportDetails(
            CapturedOutput output) throws Exception {
        try (var endpoint = new Endpoint(200);
                var timer = new ControlledScheduler();
                var queue = new LasSsoTokenRevocationQueue(timer)) {
            endpoint.dropConnection = true;
            queue.submit(registration(endpoint.uri()), "revocation-token-canary", "refresh_token");

            timer.runAll();

            // A low-level client may retry a connection internally, but application retries are
            // bounded at eight.
            assertThat(timer.retryDelays).hasSize(7);
            assertThat(output.getAll())
                    .contains("errorCode=ResourceAccessException")
                    .doesNotContain("revocation-token-canary", "b3-test-secret", endpoint.uri());
        }
    }

    /**
     * Executes real HTTP tasks one at a time, recording rather than waiting out random retry
     * delays.
     */
    private static final class ControlledScheduler extends ScheduledThreadPoolExecutor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<Long> retryDelays = new ArrayList<>();

        ControlledScheduler() {
            super(1);
        }

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            retryDelays.add(unit.toSeconds(delay));
            var task = new ScheduledTask(command);
            tasks.add(task);
            return task;
        }

        void runNext() {
            tasks.removeFirst().run();
        }

        void runAll() {
            int count = 0;
            while (!tasks.isEmpty()) {
                assertThat(++count).isLessThanOrEqualTo(8);
                var task = tasks.removeFirst();
                task.run();
                if (task instanceof ScheduledTask scheduled) {
                    assertThat(scheduled.isDone()).isTrue();
                    try {
                        scheduled.get();
                    } catch (Exception exception) {
                        throw new AssertionError(
                                "Scheduled revocation task failed unexpectedly", exception);
                    }
                }
            }
        }
    }

    private static final class ScheduledTask extends FutureTask<Void>
            implements ScheduledFuture<Void> {
        ScheduledTask(Runnable command) {
            super(command, null);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }

    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger redirects = new AtomicInteger();
        final List<String> methods = new CopyOnWriteArrayList<>();
        final List<String> auth = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        volatile int status;
        volatile boolean dropConnection;

        Endpoint(int status) throws Exception {
            this.status = status;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/revoke",
                    exchange -> {
                        methods.add(exchange.getRequestMethod());
                        auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                        bodies.add(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        calls.incrementAndGet();
                        if (dropConnection) {
                            exchange.close();
                            return;
                        }
                        exchange.getResponseHeaders()
                                .set("Location", uri().replace("/revoke", "/login"));
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
                        redirects.incrementAndGet();
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                    });
            server.start();
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/revoke";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static ClientRegistration registration(String uri) {
        return ClientRegistration.withRegistrationId("b3-test")
                .clientId("b3-test-client")
                .clientSecret("b3-test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/callback")
                .authorizationUri("https://issuer.example/authorize")
                .tokenUri("https://issuer.example/token")
                .providerConfigurationMetadata(Map.of("revocation_endpoint", uri))
                .build();
    }
}
