package org.las.sso.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.config.FirstPartyAuthenticationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        properties = {"las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(60)
class FirstPartyClientRateLimitIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RegisteredClientRepository clients;
    @MockitoSpyBean PasswordEncoder encoder;
    @MockitoSpyBean FirstPartyAuthenticationBudget budget;
    @Autowired TransientSecurityKeyHasher hasher;
    @Autowired PostgresRateLimiter limiter;
    @Autowired ClientNetworkKey networks;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearOwnBuckets() {
        jdbc.sql(
                        "DELETE FROM rate_limit_buckets WHERE scope IN ('FIRST_PARTY_AUTH_CLIENT', 'FIRST_PARTY_AUTH_NETWORK')")
                .update();
    }

    @AfterEach
    void clearSpyStubs() {
        reset(budget, encoder);
    }

    @Test
    void repeatedWrongSecretsAreRejectedBeforeAnotherPasswordHashVerification() throws Exception {
        var client = client();
        clearInvocations(encoder);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(
                            mvc.perform(
                                            get(path())
                                                    .with(
                                                            httpBasic(
                                                                    client.getClientId(),
                                                                    "a3-wrong-secret"))
                                                    .with(
                                                            request -> {
                                                                request.setRemoteAddr("192.0.2.10");
                                                                return request;
                                                            }))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(401);
        }

        var rejected =
                mvc.perform(
                                get(path())
                                        .with(httpBasic(client.getClientId(), "a3-wrong-secret"))
                                        .with(
                                                request -> {
                                                    request.setRemoteAddr("192.0.2.10");
                                                    return request;
                                                }))
                        .andReturn()
                        .getResponse();

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(Long.parseLong(rejected.getHeader("Retry-After"))).isBetween(1L, 900L);
        verify(encoder, times(10)).matches(eq("a3-wrong-secret"), eq(client.getClientSecret()));
        assertThat(rejected.getContentAsString()).contains("RATE_LIMITED");
        assertThat(rejected.getContentAsString())
                .contains("\"requestId\":\"" + rejected.getHeader("X-Request-Id") + "\"");
        assertThat(UUID.fromString(rejected.getHeader("X-Request-Id"))).isNotNull();
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, client.getClientId()))
                .isEqualTo(10);
        assertThat(
                        count(
                                FirstPartyAuthenticationBudget.NETWORK_SCOPE,
                                networks.fromRemoteAddress("192.0.2.10")))
                .isEqualTo(10);
    }

    @Test
    void moreThanTwentySuccessfulQueriesDoNotAccumulateFailures() throws Exception {
        var client = client();
        clearInvocations(encoder);
        for (int request = 0; request < 25; request++) {
            assertThat(query(client, "a3-correct-secret", "198.51.100.10")).isEqualTo(200);
        }
        verify(encoder, times(25)).matches(eq("a3-correct-secret"), eq(client.getClientSecret()));
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, client.getClientId()))
                .isZero();
        assertThat(
                        count(
                                FirstPartyAuthenticationBudget.NETWORK_SCOPE,
                                networks.fromRemoteAddress("198.51.100.10")))
                .isZero();
    }

    @Test
    void successRefundDoesNotEraseEarlierFailuresAndClientLimitAppliesAcrossNetworks()
            throws Exception {
        var client = client();
        for (int failure = 0; failure < 9; failure++) {
            assertThat(query(client, "a3-wrong-secret", "198.18.1.10")).isEqualTo(401);
        }
        assertThat(query(client, "a3-correct-secret", "198.18.2.10")).isEqualTo(200);
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, client.getClientId()))
                .isEqualTo(9);
        assertThat(
                        count(
                                FirstPartyAuthenticationBudget.NETWORK_SCOPE,
                                networks.fromRemoteAddress("198.18.2.10")))
                .isZero();
        assertThat(query(client, "a3-wrong-secret", "198.18.3.10")).isEqualTo(401);
        clearInvocations(encoder);

        assertThat(query(client, "a3-correct-secret", "198.18.4.10")).isEqualTo(429);

        verify(encoder, times(0)).matches(any(), anyString());
        // Client exhaustion must roll back the new network reservation, not charge a partial
        // attempt.
        assertThat(
                        count(
                                FirstPartyAuthenticationBudget.NETWORK_SCOPE,
                                networks.fromRemoteAddress("198.18.4.10")))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.10", "2001:db8:1234:5600::1"})
    void networkLimitRejectsRotatingUnknownClientIdsBeforeLegitimateClientHash(String address)
            throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(
                            mvc.perform(
                                            get(path())
                                                    .with(httpBasic("unknown-" + attempt, "wrong"))
                                                    .with(
                                                            request -> {
                                                                request.setRemoteAddr(address);
                                                                return request;
                                                            }))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(401);
        }
        var client = client();
        clearInvocations(encoder);
        assertThat(query(client, "a3-correct-secret", address)).isEqualTo(429);
        verify(encoder, times(0)).matches(any(), anyString());
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, client.getClientId()))
                .isZero();
        assertThat(
                        count(
                                FirstPartyAuthenticationBudget.NETWORK_SCOPE,
                                networks.fromRemoteAddress(address)))
                .isEqualTo(20);
        assertThat(query(client, "a3-correct-secret", "198.19.0.10")).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "invalid-base64",
                "empty-secret",
                "oversized",
                "multiple",
                "blank-client",
                "long-client",
                "client-control"
            })
    void malformedOrMissingCredentialsUseUnknownBudgetWithoutHashing(String malformed)
            throws Exception {
        var client = client();
        clearInvocations(encoder);
        for (int attempt = 0; attempt < 11; attempt++) {
            var request =
                    get(path())
                            .with(
                                    req -> {
                                        req.setRemoteAddr("192.0.2.120");
                                        return req;
                                    });
            switch (malformed) {
                case "invalid-base64" -> request.header("Authorization", "Basic !bad!");
                case "empty-secret" -> request.with(httpBasic(client.getClientId(), ""));
                case "oversized" -> request.header("Authorization", "Basic " + "A".repeat(4096));
                case "multiple" -> request.header("Authorization", "Basic YTpi", "Basic Yzpk");
                case "blank-client" -> request.with(httpBasic(" ", "secret"));
                case "long-client" -> request.with(httpBasic("a".repeat(129), "secret"));
                case "client-control" -> request.with(httpBasic("client\r\ncanary", "secret"));
                default -> {}
            }
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
                    .isEqualTo(attempt < 10 ? 401 : 429);
        }
        verify(encoder, times(0)).matches(any(), anyString());
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, "<unknown>")).isEqualTo(10);
    }

    @Test
    void nonFirstPartyOrInactiveClientsDoNotReachHashButConsumeFailureBudget() throws Exception {
        var client = client();
        jdbc.sql(
                        "UPDATE registered_client_metadata SET client_type = 'REVIEWED_PARTNER' WHERE registered_client_id = :id")
                .param("id", client.getId())
                .update();
        clearInvocations(encoder);
        assertThat(query(client, "a3-correct-secret", "192.0.2.160")).isEqualTo(401);
        jdbc.sql(
                        "UPDATE registered_client_metadata SET client_type = 'FIRST_PARTY', status = 'DISABLED' "
                                + "WHERE registered_client_id = :id")
                .param("id", client.getId())
                .update();
        assertThat(query(client, "a3-correct-secret", "192.0.2.160")).isEqualTo(401);
        verify(encoder, times(0)).matches(any(), anyString());
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, client.getClientId()))
                .isEqualTo(2);
    }

    @Test
    void encodedInternalPathUsesTheSameExhaustedBudget() throws Exception {
        var client = client();
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(query(client, "a3-wrong-secret", "192.0.2.170")).isEqualTo(401);
        }
        clearInvocations(encoder);
        var response =
                mvc.perform(
                                get(URI.create(path().replace("/principals/", "/%70rincipals/")))
                                        .with(httpBasic(client.getClientId(), "a3-correct-secret"))
                                        .with(
                                                request -> {
                                                    request.setRemoteAddr("192.0.2.170");
                                                    return request;
                                                }))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(429);
        verify(encoder, times(0)).matches(any(), anyString());
    }

    @Test
    void oauthFailureBucketsAndInternalBudgetAreIndependent() throws Exception {
        var client = client();
        for (int failure = 0; failure < 10; failure++) {
            limiter.consume(
                    "CLIENT_SECRET_FAILURE_CLIENT",
                    client.getClientId(),
                    10,
                    Duration.ofMinutes(15));
        }
        assertThat(query(client, "a3-correct-secret", "198.18.50.1")).isEqualTo(200);
        for (int failure = 0; failure < 10; failure++) {
            assertThat(query(client, "a3-wrong-secret", "198.18.50.1")).isEqualTo(401);
        }
        assertThat(
                        limiter.current(
                                        "CLIENT_SECRET_FAILURE_CLIENT",
                                        client.getClientId(),
                                        10,
                                        Duration.ofMinutes(15))
                                .count())
                .isEqualTo(10);
        assertThat(
                        limiter.current(
                                        "CLIENT_SECRET_FAILURE_IP",
                                        networks.fromRemoteAddress("198.18.50.1"),
                                        20,
                                        Duration.ofMinutes(15))
                                .count())
                .isZero();
    }

    @Test
    void concurrentFailedRequestsCannotAllPassTheHashGate() throws Exception {
        var client = client();
        var enteredHash = new CountDownLatch(10);
        var releaseHash = new CountDownLatch(1);
        clearInvocations(encoder);
        doAnswer(
                        invocation -> {
                            enteredHash.countDown();
                            if (!releaseHash.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("Hash fixture release timed out");
                            return invocation.callRealMethod();
                        })
                .when(encoder)
                .matches(eq("a3-wrong-secret"), eq(client.getClientSecret()));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var responses = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int request = 0; request < 25; request++) {
                responses.add(
                        executor.submit(() -> query(client, "a3-wrong-secret", "198.18.60.1")));
            }
            try {
                assertThat(enteredHash.await(10, TimeUnit.SECONDS)).isTrue();
                // Hash tasks are paused; admission transactions have already released their DB
                // locks.
                assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, client.getClientId()))
                        .isEqualTo(10);
            } finally {
                releaseHash.countDown();
            }
            int unauthorized = 0;
            int rejected = 0;
            for (var task : responses) {
                int status = task.get(15, TimeUnit.SECONDS);
                if (status == 401) unauthorized++;
                else if (status == 429) rejected++;
                else throw new AssertionError("Unexpected concurrent response status " + status);
            }
            assertThat(unauthorized).isEqualTo(10);
            assertThat(rejected).isEqualTo(15);
            verify(encoder, times(10)).matches(eq("a3-wrong-secret"), eq(client.getClientSecret()));
        }
    }

    @Test
    void databaseFailureBeforeReservationStopsAuthenticationAndDoesNotLeakSecrets(
            CapturedOutput output) throws Exception {
        var client = client();
        clearInvocations(encoder);
        doThrow(new DataAccessResourceFailureException("db-secret-canary"))
                .when(budget)
                .reserve(eq(client.getClientId()), anyString());

        var response =
                mvc.perform(get(path()).with(httpBasic(client.getClientId(), "a3-correct-secret")))
                        .andReturn()
                        .getResponse();

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString())
                .contains("IDENTITY_SERVICE_UNAVAILABLE")
                .doesNotContain("db-secret-canary", "a3-correct-secret");
        assertThat(response.getContentAsString())
                .contains("\"requestId\":\"" + response.getHeader("X-Request-Id") + "\"");
        verify(encoder, times(0)).matches(any(), anyString());
        assertThat(output.getAll()).doesNotContain("db-secret-canary");
    }

    @Test
    void refundFailureDoesNotAuthorizeAnOtherwiseValidClient(CapturedOutput output)
            throws Exception {
        var client = client();
        doThrow(new DataAccessResourceFailureException("refund-secret-canary"))
                .when(budget)
                .release(any());

        assertThat(query(client, "a3-correct-secret", "198.18.70.1")).isEqualTo(503);

        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, client.getClientId()))
                .isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("refund-secret-canary");
    }

    @Test
    void actualSecondBucketDatabaseFailureRollsBackFirstBucketBeforeAnyHash(CapturedOutput output)
            throws Exception {
        var client = client();
        clearInvocations(encoder);
        // Isolated Testcontainers fixture: provoke a real PostgreSQL write error on the second
        // bucket.
        jdbc.sql(
                        """
                ALTER TABLE rate_limit_buckets ADD CONSTRAINT a3_fixture_block_client
                    CHECK (scope <> 'FIRST_PARTY_AUTH_CLIENT') NOT VALID
                """)
                .update();
        try {
            var response =
                    mvc.perform(
                                    get(path())
                                            .with(
                                                    httpBasic(
                                                            client.getClientId(),
                                                            "a3-correct-secret"))
                                            .with(
                                                    request -> {
                                                        request.setRemoteAddr("198.18.80.1");
                                                        return request;
                                                    }))
                            .andReturn()
                            .getResponse();

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getContentAsString())
                    .doesNotContain("a3_fixture_block_client", "SQL", "a3-correct-secret");
            verify(encoder, times(0)).matches(any(), anyString());
            assertThat(
                            count(
                                    FirstPartyAuthenticationBudget.NETWORK_SCOPE,
                                    networks.fromRemoteAddress("198.18.80.1")))
                    .isZero();
            assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, client.getClientId()))
                    .isZero();
            assertThat(output.getAll()).doesNotContain("a3_fixture_block_client");
        } finally {
            jdbc.sql("ALTER TABLE rate_limit_buckets DROP CONSTRAINT a3_fixture_block_client")
                    .update();
        }
        assertThat(query(client, "a3-correct-secret", "198.18.80.1")).isEqualTo(200);
    }

    @Test
    void actualRefundDatabaseFailureRollsBackBothBucketsAndReturnsUnavailable(CapturedOutput output)
            throws Exception {
        var client = client();
        jdbc.sql(
                        """
                CREATE FUNCTION a3_fixture_refund_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF OLD.scope = 'FIRST_PARTY_AUTH_CLIENT' THEN
                        RAISE EXCEPTION 'refund-sql-secret-canary';
                    END IF;
                    RETURN OLD;
                END;
                $$
                """)
                .update();
        try {
            jdbc.sql(
                            """
                    CREATE TRIGGER a3_fixture_refund_failure BEFORE DELETE ON rate_limit_buckets
                        FOR EACH ROW EXECUTE FUNCTION a3_fixture_refund_failure()
                    """)
                    .update();
            try {
                var response =
                        mvc.perform(
                                        get(path())
                                                .with(
                                                        httpBasic(
                                                                client.getClientId(),
                                                                "a3-correct-secret"))
                                                .with(
                                                        request -> {
                                                            request.setRemoteAddr("198.18.81.1");
                                                            return request;
                                                        }))
                                .andReturn()
                                .getResponse();

                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(response.getContentAsString())
                        .contains("IDENTITY_SERVICE_UNAVAILABLE")
                        .doesNotContain("refund-sql-secret-canary");
                assertThat(
                                count(
                                        FirstPartyAuthenticationBudget.NETWORK_SCOPE,
                                        networks.fromRemoteAddress("198.18.81.1")))
                        .isEqualTo(1);
                assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, client.getClientId()))
                        .isEqualTo(1);
                assertThat(output.getAll()).doesNotContain("refund-sql-secret-canary");
            } finally {
                jdbc.sql("DROP TRIGGER a3_fixture_refund_failure ON rate_limit_buckets").update();
            }
        } finally {
            jdbc.sql("DROP FUNCTION a3_fixture_refund_failure()").update();
        }
    }

    @Test
    void expiredWindowCleanupBeforeRefundDoesNotRecreateBucketsOrTouchNewWindow() {
        Instant start = Instant.parse("2026-10-04T00:00:00Z");
        var now = new AtomicReference<>(start);
        var controlled = controlledBudget(now, 2, 3);
        var old = controlled.reserve("cleanup-client", "cleanup-network");
        now.set(start.plusSeconds(900));
        var latest = controlled.reserve("cleanup-client", "cleanup-network");
        // Model only expired-window deletion; new-window rows must survive the old receipt's
        // release.
        jdbc.sql(
                        "DELETE FROM rate_limit_buckets WHERE scope IN ('FIRST_PARTY_AUTH_CLIENT', 'FIRST_PARTY_AUTH_NETWORK') "
                                + "AND window_start = :start")
                .param("start", start.atOffset(ZoneOffset.UTC))
                .update();

        controlled.release(old);

        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, "cleanup-client"))
                .isEqualTo(1);
        controlled.release(latest);
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, "cleanup-client")).isZero();
    }

    @Test
    void firstPartyAuthenticationConfigurationRejectsInvalidLimitsAndWindow() {
        assertThatThrownBy(
                        () -> new FirstPartyAuthenticationProperties(0, 20, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new FirstPartyAuthenticationProperties(10, 0, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        for (Duration invalid :
                new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(1500)}) {
            assertThatThrownBy(() -> new FirstPartyAuthenticationProperties(10, 20, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(
                        new FirstPartyAuthenticationProperties(5, 7, Duration.ofMinutes(1))
                                .clientFailures())
                .isEqualTo(5);
    }

    @Test
    void budgetRefundIsIdempotentDoesNotEraseOtherReservationsAndCannotTouchANewWindow() {
        Instant start = Instant.parse("2026-10-04T00:00:00Z");
        var now = new AtomicReference<>(start);
        var controlled = controlledBudget(now, 2, 3);
        var first = controlled.reserve("budget-client", "network");
        controlled.reserve("budget-client", "network");
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, "budget-client"))
                .isEqualTo(2);
        controlled.release(first);
        controlled.release(first);
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, "budget-client"))
                .isEqualTo(1);
        var oldSuccess = controlled.reserve("budget-client", "network");
        now.set(start.plusSeconds(900));
        controlled.reserve("budget-client", "network");
        controlled.release(oldSuccess);
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, "budget-client"))
                .isEqualTo(2);
        assertThat(
                        jdbc.sql(
                                        """
                SELECT attempt_count FROM rate_limit_buckets
                 WHERE scope = :scope AND key_hash = :key AND window_start = :start
                """)
                                .param("scope", FirstPartyAuthenticationBudget.CLIENT_SCOPE)
                                .param(
                                        "key",
                                        hasher.hash(
                                                "rate:"
                                                        + FirstPartyAuthenticationBudget
                                                                .CLIENT_SCOPE,
                                                "budget-client"))
                                .param("start", now.get().atOffset(ZoneOffset.UTC))
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void atomicBudgetDenialRollsBackBothCountersAndWindowResetsExactlyAtBoundary() {
        Instant start = Instant.parse("2026-10-04T00:00:00Z");
        var now = new AtomicReference<>(start);
        var controlled = controlledBudget(now, 1, 2);
        controlled.reserve("full-client", "network-one");
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> controlled.reserve("full-client", "network-two")))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(count(FirstPartyAuthenticationBudget.NETWORK_SCOPE, "network-two")).isZero();
        now.set(start.plusSeconds(899));
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> controlled.reserve("full-client", "network-one")))
                .isInstanceOf(RateLimitExceededException.class);
        now.set(start.plusSeconds(900));
        var success = controlled.reserve("full-client", "network-one");
        controlled.release(success);
        assertThat(count(FirstPartyAuthenticationBudget.CLIENT_SCOPE, "full-client")).isEqualTo(1);
    }

    private FirstPartyAuthenticationBudget controlledBudget(
            AtomicReference<Instant> now, int clients, int networks) {
        Clock clock =
                new Clock() {
                    @Override
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return now.get();
                    }
                };
        return new FirstPartyAuthenticationBudget(
                jdbc,
                hasher,
                new FirstPartyAuthenticationProperties(clients, networks, Duration.ofMinutes(15)),
                transactionManager,
                clock);
    }

    private int count(String scope, String key) {
        return jdbc.sql(
                        "SELECT COALESCE(SUM(attempt_count), 0) FROM rate_limit_buckets WHERE scope = :scope AND key_hash = :key")
                .param("scope", scope)
                .param("key", hasher.hash("rate:" + scope, key))
                .query(Integer.class)
                .single();
    }

    private int query(RegisteredClient client, String secret, String remote) throws Exception {
        return mvc.perform(
                        get(path())
                                .with(httpBasic(client.getClientId(), secret))
                                .with(
                                        request -> {
                                            request.setRemoteAddr(remote);
                                            return request;
                                        }))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private RegisteredClient client() {
        String id = UUID.randomUUID().toString();
        var client =
                RegisteredClient.withId(id)
                        .clientId("a3-" + id)
                        .clientSecret(encoder.encode("a3-correct-secret"))
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://client.example/callback")
                        .scope("openid")
                        .build();
        clients.save(client);
        jdbc.sql(
                        """
                INSERT INTO registered_client_metadata
                    (registered_client_id, client_type, status, config_version,
                     resource_audiences, require_pkce, updated_at)
                VALUES (:id, 'FIRST_PARTY', 'ACTIVE', 1, ARRAY['urn:las:resource:a3']::text[],
                        true, CURRENT_TIMESTAMP)
                """)
                .param("id", id)
                .update();
        return client;
    }

    private static String path() {
        return "/internal/v1/principals/019fb42f-5f16-7c31-8ce0-9e54f7441ff0/service-access";
    }
}
