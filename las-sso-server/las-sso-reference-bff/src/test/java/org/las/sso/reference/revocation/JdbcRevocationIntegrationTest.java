package org.las.sso.reference.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.client.DefaultLasSsoAuthorizedClientRepository;
import org.las.sso.client.LasSsoClientProperties;
import org.las.sso.client.LasSsoCurrentApplicationLogoutHandler;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL transactions, AES-GCM payloads and HTTP; claim expiry is accelerated by fixture
 * SQL.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class JdbcRevocationIntegrationTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String TOKEN = "durable-refresh-token-secret-canary";
    private static final String SECRET = "durable-client-secret-canary";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    static DriverManagerDataSource dataSource;
    static JdbcClient jdbc;
    JdbcRevocationStore store;
    RevocationTokenCipher cipher;

    @BeforeAll
    static void migrate() {
        dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("reference_revocation")
                .locations("classpath:revocation-db")
                .load()
                .migrate();
    }

    @BeforeEach
    void reset() {
        jdbc.sql(
                        "TRUNCATE reference_revocation.tasks, reference_revocation.key_state, reference_revocation.operator_audit")
                .update();
        cipher = new RevocationTokenCipher("test-key", KEY);
        store = newStore();
    }

    @Test
    void committedSubmissionSurvivesNewObjectsAndStoresNoPlaintextOrClientSecret()
            throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var registration = registration(endpoint.uri(), SECRET);
            var queue = new JdbcRevocationQueue(store, cipher, true);
            queue.submit(registration, TOKEN, "refresh_token");
            UUID id = id();
            String persisted =
                    jdbc.sql("SELECT row_to_json(task)::text FROM reference_revocation.tasks task")
                            .query(String.class)
                            .single();
            assertThat(persisted).doesNotContain(TOKEN, SECRET);
            assertThat(endpoint.calls).hasValue(0);
            new RevocationWorker(
                            newStore(),
                            new RevocationTokenCipher("test-key", KEY),
                            new InMemoryClientRegistrationRepository(registration),
                            true)
                    .poll();
            assertThat(state(id).get("completed")).isEqualTo(true);
            assertThat(state(id).get("encrypted")).isEqualTo(false);
            assertThat(endpoint.calls).hasValue(1);
            assertThat(endpoint.bodies)
                    .containsExactly("token=" + TOKEN + "&token_type_hint=refresh_token");
            assertThat(endpoint.auth)
                    .containsExactly(
                            "Basic "
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    ("reference-client:" + SECRET)
                                                            .getBytes(StandardCharsets.UTF_8)));
            worker(registration).poll();
            assertThat(endpoint.calls).hasValue(1);
        }
    }

    @Test
    void acceptanceCommitsBeforeReturnEvenWhenOuterTransactionRollsBack() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var outer = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            outer.executeWithoutResult(
                    status -> {
                        queue().submit(
                                        registration(endpoint.uri(), SECRET),
                                        TOKEN,
                                        "refresh_token");
                        // Read on an independent connection, not the caller transaction's
                        // uncommitted view.
                        assertThat(
                                        JdbcClient.create(
                                                        new DriverManagerDataSource(
                                                                POSTGRES.getJdbcUrl(),
                                                                POSTGRES.getUsername(),
                                                                POSTGRES.getPassword()))
                                                .sql(
                                                        "SELECT count(*) FROM reference_revocation.tasks")
                                                .query(Long.class)
                                                .single())
                                .isEqualTo(1);
                        status.setRollbackOnly();
                    });
            assertThat(count()).isEqualTo(1);
        }
    }

    @Test
    void insertFailureReportsRejectedAcceptanceWithoutSqlOrTokenLeak() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            fault("INSERT", "true");
            try {
                assertThatThrownBy(
                                () ->
                                        queue().submit(
                                                        registration(endpoint.uri(), SECRET),
                                                        TOKEN,
                                                        "refresh_token"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Durable revocation acceptance failed")
                        .hasNoCause();
                assertThat(count()).isZero();
                assertThat(endpoint.calls).hasValue(0);
            } finally {
                removeFault();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 202, 204, 206, 301, 302, 303, 307, 308, 400, 401, 429, 500, 503})
    void onlyExact200CompletesAndRedirectsNeverForwardTheToken(int status) throws Exception {
        try (var endpoint = new Endpoint(status)) {
            var registration = registration(endpoint.uri(), SECRET);
            queue().submit(registration, TOKEN, "refresh_token");
            UUID id = id();
            worker(registration).poll();
            assertThat(state(id))
                    .containsEntry("completed", false)
                    .containsEntry("dead", false)
                    .containsEntry("claimed", false)
                    .containsEntry("attempts", 1)
                    .containsEntry("error", "HTTP_" + status);
            assertThat(endpoint.redirects).hasValue(0);
            var times =
                    jdbc.sql(
                                    "SELECT available_at, CURRENT_TIMESTAMP AS now FROM reference_revocation.tasks WHERE id=:id")
                            .param("id", id)
                            .query(
                                    (rs, row) ->
                                            new OffsetDateTime[] {
                                                rs.getObject("available_at", OffsetDateTime.class),
                                                rs.getObject("now", OffsetDateTime.class)
                                            })
                            .single();
            assertThat(times[0]).isAfter(times[1]).isBeforeOrEqualTo(times[1].plusSeconds(2));
            endpoint.status = 200;
            due(id);
            worker(registration).poll();
            assertThat(state(id)).containsEntry("completed", true).containsEntry("attempts", 2);
            assertThat(endpoint.calls).hasValue(2);
        }
    }

    @Test
    void retriesPersistAcrossWorkersAndEightFailuresReachDeadLetter(CapturedOutput output)
            throws Exception {
        try (var endpoint = new Endpoint(503)) {
            var registration = registration(endpoint.uri(), SECRET);
            queue().submit(registration, TOKEN, "refresh_token");
            UUID id = id();
            for (int attempt = 1; attempt <= 8; attempt++) {
                due(id);
                worker(registration).poll();
                assertThat(state(id))
                        .containsEntry("attempts", attempt)
                        .containsEntry("dead", attempt == 8)
                        .containsEntry("completed", false)
                        .containsEntry("claimed", false);
            }
            due(id);
            worker(registration).poll();
            assertThat(endpoint.calls).hasValue(8);
            assertThat(state(id).get("encrypted"))
                    .isEqualTo(true); // encrypted dead-letter recovery material
            assertThat(output.getAll())
                    .contains("errorCode=HTTP_503")
                    .doesNotContain(
                            TOKEN, SECRET, "http-response-secret-canary", "sql-bind-secret-canary");
        }
    }

    @Test
    void disconnectedHttpIsRetryableWithoutLeakingEndpointOrToken(CapturedOutput output)
            throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var registration = registration(endpoint.uri(), SECRET);
            queue().submit(registration, TOKEN, "refresh_token");
            UUID id = id();
            endpoint.close();
            worker(registration).poll();
            assertThat(state(id))
                    .containsEntry("error", "TRANSPORT_UNAVAILABLE")
                    .containsEntry("claimed", false)
                    .containsEntry("dead", false)
                    .containsEntry("attempts", 1);
            assertThat(output.getAll()).doesNotContain(TOKEN, SECRET, endpoint.uri());
        }
    }

    @Test
    void claimWriteFailureRollsBackReservationAndLeavesTaskAvailable(CapturedOutput output)
            throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var registration = registration(endpoint.uri(), SECRET);
            queue().submit(registration, TOKEN, "refresh_token");
            UUID id = id();
            fault("UPDATE", "NEW.claim_token IS NOT NULL");
            try {
                worker(registration).poll();
                assertThat(state(id)).containsEntry("attempts", 0).containsEntry("claimed", false);
                assertThat(endpoint.calls).hasValue(0);
                assertThat(output.getAll()).doesNotContain("sql-bind-secret-canary", TOKEN, SECRET);
            } finally {
                removeFault();
            }
            worker(registration).poll();
            assertThat(state(id)).containsEntry("completed", true).containsEntry("attempts", 1);
        }
    }

    @Test
    void failedRetryReceiptRemainsLeasedAndCanRecoverWithoutLosingTask() throws Exception {
        try (var endpoint = new Endpoint(503)) {
            var registration = registration(endpoint.uri(), SECRET);
            queue().submit(registration, TOKEN, "refresh_token");
            UUID id = id();
            fault("UPDATE", "NEW.last_error_code IS NOT NULL");
            try {
                worker(registration).poll();
                assertThat(state(id))
                        .containsEntry("claimed", true)
                        .containsEntry("attempts", 1)
                        .containsEntry("dead", false);
            } finally {
                removeFault();
            }
            expireLease(id);
            endpoint.status = 200;
            worker(registration).poll();
            assertThat(state(id)).containsEntry("completed", true).containsEntry("attempts", 2);
        }
    }

    @Test
    void expiredOwnerCannotCompleteOrRescheduleEvenBeforeAnotherWorkerClaims() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            queue().submit(registration(endpoint.uri(), SECRET), TOKEN, "refresh_token");
            var task = store.claim(RevocationWorker.LEASE).orElseThrow();
            expireLease(task.id());
            assertThat(store.complete(task)).isFalse();
            assertThat(store.fail(task, "HTTP_503", 1)).isFalse();
            assertThat(state(task.id()))
                    .containsEntry("completed", false)
                    .containsEntry("claimed", true);
        }
    }

    @Test
    void ciphertextCopiedBetweenTaskIdsCannotBeAuthenticated() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var registration = registration(endpoint.uri(), SECRET);
            queue().submit(registration, TOKEN, "refresh_token");
            queue().submit(registration, TOKEN + "-other", "refresh_token");
            var tasks =
                    jdbc.sql("SELECT id FROM reference_revocation.tasks ORDER BY id")
                            .query(UUID.class)
                            .list();
            jdbc.sql(
                            """
                    UPDATE reference_revocation.tasks target SET nonce=source.nonce, ciphertext=source.ciphertext
                      FROM reference_revocation.tasks source WHERE target.id=:target AND source.id=:source
                    """)
                    .param("target", tasks.getFirst())
                    .param("source", tasks.getLast())
                    .update();
            worker(registration).poll();
            assertThat(state(tasks.getFirst())).containsEntry("error", "PAYLOAD_UNAVAILABLE");
            assertThat(state(tasks.getLast())).containsEntry("completed", true);
            assertThat(endpoint.calls).hasValue(1);
        }
    }

    @Test
    void invalidEncryptionKeysHaveSanitizedErrors() {
        for (String invalid :
                java.util.List.of(
                        "not-base64-secret-canary",
                        Base64.getEncoder().encodeToString(new byte[16]))) {
            assertThatThrownBy(() -> new RevocationTokenCipher("test-key", invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain(invalid));
        }
    }

    @Test
    void aClaimedTaskRecoversAfterLeaseExpiryAndOldOwnerCannotAcknowledgeIt() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var registration = registration(endpoint.uri(), SECRET);
            queue().submit(registration, TOKEN, "refresh_token");
            var old = store.claim(RevocationWorker.LEASE).orElseThrow();
            assertThat(newStore().claim(RevocationWorker.LEASE)).isEmpty();
            expireLease(old.id());
            var next = newStore().claim(RevocationWorker.LEASE).orElseThrow();
            assertThat(next.claimToken()).isNotEqualTo(old.claimToken());
            assertThat(store.complete(old)).isFalse();
            assertThat(store.fail(old, "HTTP_503", 1)).isFalse();
            worker(registration).deliver(next);
            assertThat(state(old.id()))
                    .containsEntry("completed", true)
                    .containsEntry("attempts", 2);
            assertThat(endpoint.calls).hasValue(1);
        }
    }

    @Test
    void concurrentDatabaseClaimersHaveOneOwnerAndOtherTasksDoNotBlock() throws Exception {
        try (var endpoint = new Endpoint(200);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            queue().submit(registration(endpoint.uri(), SECRET), TOKEN, "refresh_token");
            var barrier = new CyclicBarrier(2);
            var a =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return newStore().claim(RevocationWorker.LEASE);
                            });
            var b =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return newStore().claim(RevocationWorker.LEASE);
                            });
            assertThat(
                            (a.get(5, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                                    + (b.get(5, TimeUnit.SECONDS).isPresent() ? 1 : 0))
                    .isEqualTo(1);
            queue().submit(registration(endpoint.uri(), SECRET), TOKEN + "-other", "refresh_token");
            assertThat(newStore().claim(RevocationWorker.LEASE)).isPresent();
        }
    }

    @Test
    void successfulHttpWithFailedReceiptCommitRetriesAfterLeaseRecovery(CapturedOutput output)
            throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var registration = registration(endpoint.uri(), SECRET);
            queue().submit(registration, TOKEN, "refresh_token");
            UUID id = id();
            fault("UPDATE", "NEW.completed_at IS NOT NULL");
            try {
                worker(registration).poll();
                assertThat(state(id))
                        .containsEntry("completed", false)
                        .containsEntry("claimed", true)
                        .containsEntry("attempts", 1);
                assertThat(endpoint.calls).hasValue(1);
            } finally {
                removeFault();
            }
            expireLease(id);
            worker(registration).poll();
            assertThat(state(id)).containsEntry("completed", true).containsEntry("attempts", 2);
            assertThat(endpoint.calls)
                    .hasValue(2); // RFC7009 idempotent replay; no exactly-once claim
            assertThat(output.getAll()).doesNotContain("sql-bind-secret-canary", TOKEN, SECRET);
        }
    }

    @Test
    void crashOnEighthClaimBecomesDeadLetterWithoutNinthHttpAttempt() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            queue().submit(registration(endpoint.uri(), SECRET), TOKEN, "refresh_token");
            UUID id = id();
            jdbc.sql("UPDATE reference_revocation.tasks SET attempt_count=7 WHERE id=:id")
                    .param("id", id)
                    .update();
            assertThat(store.claim(RevocationWorker.LEASE).orElseThrow().attempts()).isEqualTo(8);
            expireLease(id);
            worker(registration(endpoint.uri(), SECRET)).poll();
            assertThat(state(id))
                    .containsEntry("dead", true)
                    .containsEntry("attempts", 8)
                    .containsEntry("error", "LEASE_EXHAUSTED");
            assertThat(endpoint.calls).hasValue(0);
        }
    }

    @Test
    void encryptedMetadataTamperOrMissingKeyNeverSendsToken() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var registration = registration(endpoint.uri(), SECRET);
            queue().submit(registration, TOKEN, "refresh_token");
            UUID id = id();
            jdbc.sql(
                            "UPDATE reference_revocation.tasks SET token_type_hint='access_token' WHERE id=:id")
                    .param("id", id)
                    .update();
            worker(registration).poll();
            assertThat(state(id)).containsEntry("error", "PAYLOAD_UNAVAILABLE");
            assertThat(endpoint.calls).hasValue(0);
            jdbc.sql("TRUNCATE reference_revocation.tasks").update();
            queue().submit(registration, TOKEN, "refresh_token");
            var missingKey =
                    new RevocationWorker(
                            newStore(),
                            new RevocationTokenCipher("different-key", KEY),
                            new InMemoryClientRegistrationRepository(registration),
                            true);
            missingKey.deliver(store.claim(RevocationWorker.LEASE).orElseThrow());
            assertThat(state(id())).containsEntry("error", "PAYLOAD_UNAVAILABLE");
            assertThat(endpoint.calls).hasValue(0);
        }
    }

    @Test
    void taskNeverMovesToAnUnexpectedIssuerClientOrEndpointButSecretRotationIsSupported()
            throws Exception {
        try (var endpoint = new Endpoint(200);
                var other = new Endpoint(200)) {
            var original = registration(endpoint.uri(), SECRET);
            queue().submit(original, TOKEN, "refresh_token");
            UUID id = id();
            worker(registration(other.uri(), SECRET)).poll();
            assertThat(state(id)).containsEntry("error", "CONFIGURATION_CHANGED");
            assertThat(other.calls).hasValue(0);
            assertThat(endpoint.calls).hasValue(0);
            due(id);
            worker(registration(endpoint.uri(), "rotated-client-secret-canary")).poll();
            assertThat(state(id)).containsEntry("completed", true);
            assertThat(endpoint.auth.getFirst())
                    .isEqualTo(
                            "Basic "
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    "reference-client:rotated-client-secret-canary"
                                                            .getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void ciphertextUsesFreshNonceForIdenticalSubmissionsAndWrongKeyFailsAuthentication()
            throws Exception {
        try (var endpoint = new Endpoint(200)) {
            queue().submit(registration(endpoint.uri(), SECRET), TOKEN, "refresh_token");
            queue().submit(registration(endpoint.uri(), SECRET), TOKEN, "refresh_token");
            var first = store.claim(RevocationWorker.LEASE).orElseThrow();
            var second = store.claim(RevocationWorker.LEASE).orElseThrow();
            assertThat(first.nonce()).isNotEqualTo(second.nonce());
            assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
            byte[] otherKey = new byte[32];
            otherKey[0] = 1;
            assertThatThrownBy(
                            () ->
                                    new RevocationTokenCipher(
                                                    "test-key",
                                                    Base64.getEncoder().encodeToString(otherKey))
                                            .decrypt(first))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Revocation payload unavailable");
        }
    }

    @Test
    void missingOrChangedRegistrationCannotSendTheSnapshotToAnotherIdentity() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var original = registration(endpoint.uri(), SECRET);
            for (ClientRegistrationRepository repository :
                    java.util.List.<ClientRegistrationRepository>of(
                            id -> null,
                            new InMemoryClientRegistrationRepository(
                                    ClientRegistration.withClientRegistration(original)
                                            .issuerUri("https://different-issuer.example")
                                            .build()),
                            new InMemoryClientRegistrationRepository(
                                    ClientRegistration.withClientRegistration(original)
                                            .clientId("different-client")
                                            .build()))) {
                jdbc.sql("TRUNCATE reference_revocation.tasks").update();
                queue().submit(original, TOKEN, "refresh_token");
                new RevocationWorker(newStore(), cipher, repository, true).poll();
                assertThat(state(id()))
                        .containsEntry("error", "CONFIGURATION_CHANGED")
                        .containsEntry("completed", false);
                assertThat(endpoint.calls).hasValue(0);
            }
        }
    }

    @Test
    void localLogoutRemainsCompleteWhenDurableSubmissionFails(CapturedOutput output)
            throws Exception {
        try (var endpoint = new Endpoint(200)) {
            var registration = registration(endpoint.uri(), SECRET);
            var request = new MockHttpServletRequest();
            request.getSession();
            var response = new MockHttpServletResponse();
            var principal =
                    UsernamePasswordAuthenticationToken.authenticated(
                            "subject", null, java.util.List.of());
            var repository = new DefaultLasSsoAuthorizedClientRepository();
            var now = java.time.Instant.now();
            repository.saveAuthorizedClient(
                    new OAuth2AuthorizedClient(
                            registration,
                            "subject",
                            new OAuth2AccessToken(
                                    OAuth2AccessToken.TokenType.BEARER,
                                    "access-token",
                                    now,
                                    now.plusSeconds(900)),
                            new OAuth2RefreshToken(TOKEN, now)),
                    principal,
                    request,
                    response);
            SecurityContextHolder.getContext().setAuthentication(principal);
            fault("INSERT", "true");
            try {
                new LasSsoCurrentApplicationLogoutHandler(
                                new InMemoryClientRegistrationRepository(registration),
                                repository,
                                new LasSsoClientProperties("sample", Duration.ofSeconds(60)),
                                queue())
                        .logout(request, response, principal);
                assertThat(request.getSession(false)).isNull();
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                assertThat(count()).isZero();
                assertThat(
                                request.getAttribute(
                                        org.las.sso.client.LasSsoRevocationSubmission
                                                .REQUEST_ATTRIBUTE))
                        .isEqualTo(org.las.sso.client.LasSsoRevocationSubmission.FAILED);
                assertThat(output.getAll()).doesNotContain(TOKEN, SECRET, "sql-bind-secret-canary");
            } finally {
                removeFault();
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void unsafeEndpointsAndInvalidTokenMaterialRejectBeforeAcceptance() throws Exception {
        try (var endpoint = new Endpoint(200)) {
            for (String unsafe :
                    java.util.List.of(
                            "http://example.com/revoke",
                            "https://example.com/revoke?token=x",
                            "https://user@example.com/revoke",
                            "https://example.com/revoke#fragment")) {
                assertThatThrownBy(
                                () ->
                                        queue().submit(
                                                        registration(unsafe, SECRET),
                                                        TOKEN,
                                                        "refresh_token"))
                        .isInstanceOf(IllegalStateException.class);
            }
            assertThatThrownBy(
                            () ->
                                    new JdbcRevocationQueue(store, cipher, false)
                                            .submit(
                                                    registration(endpoint.uri(), SECRET),
                                                    TOKEN,
                                                    "refresh_token"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(
                            () ->
                                    queue().submit(
                                                    registration(endpoint.uri(), SECRET),
                                                    "",
                                                    "refresh_token"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(
                            () ->
                                    queue().submit(
                                                    registration(endpoint.uri(), SECRET),
                                                    TOKEN,
                                                    "unknown"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(count()).isZero();
        }
    }

    private JdbcRevocationStore newStore() {
        return new JdbcRevocationStore(jdbc, new DataSourceTransactionManager(dataSource), cipher);
    }

    private JdbcRevocationQueue queue() {
        return new JdbcRevocationQueue(store, cipher, true);
    }

    private RevocationWorker worker(ClientRegistration registration) {
        return new RevocationWorker(
                newStore(),
                new RevocationTokenCipher("test-key", KEY),
                new InMemoryClientRegistrationRepository(registration),
                true);
    }

    private UUID id() {
        return jdbc.sql("SELECT id FROM reference_revocation.tasks").query(UUID.class).single();
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM reference_revocation.tasks")
                .query(Long.class)
                .single();
    }

    private void due(UUID id) {
        jdbc.sql(
                        "UPDATE reference_revocation.tasks SET available_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=:id")
                .param("id", id)
                .update();
    }

    private void expireLease(UUID id) {
        jdbc.sql(
                        "UPDATE reference_revocation.tasks SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=:id")
                .param("id", id)
                .update();
    }

    private Map<String, Object> state(UUID id) {
        return jdbc.sql(
                        """
                SELECT completed_at IS NOT NULL AS completed, dead_lettered_at IS NOT NULL AS dead,
                       claim_token IS NOT NULL AS claimed, ciphertext IS NOT NULL AS encrypted,
                       attempt_count AS attempts, last_error_code AS error
                  FROM reference_revocation.tasks WHERE id=:id
                """)
                .param("id", id)
                .query(
                        (rs, row) -> {
                            var map = new java.util.HashMap<String, Object>();
                            map.put("completed", rs.getBoolean("completed"));
                            map.put("dead", rs.getBoolean("dead"));
                            map.put("claimed", rs.getBoolean("claimed"));
                            map.put("encrypted", rs.getBoolean("encrypted"));
                            map.put("attempts", rs.getInt("attempts"));
                            map.put("error", rs.getString("error"));
                            return map;
                        })
                .single();
    }

    private void fault(String operation, String condition) {
        jdbc.sql(
                        "CREATE OR REPLACE FUNCTION reference_revocation.reject_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ "
                                + "BEGIN IF "
                                + condition
                                + " THEN RAISE EXCEPTION 'sql-bind-secret-canary'; END IF; RETURN NEW; END $$")
                .update();
        jdbc.sql(
                        "CREATE TRIGGER fixture_fault BEFORE "
                                + operation
                                + " ON reference_revocation.tasks FOR EACH ROW EXECUTE FUNCTION reference_revocation.reject_fixture()")
                .update();
    }

    private void removeFault() {
        jdbc.sql("DROP TRIGGER IF EXISTS fixture_fault ON reference_revocation.tasks").update();
        jdbc.sql("DROP FUNCTION IF EXISTS reference_revocation.reject_fixture()").update();
    }

    static ClientRegistration registration(String uri, String secret) {
        return ClientRegistration.withRegistrationId("sample")
                .clientId("reference-client")
                .clientSecret(secret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/callback")
                .authorizationUri("https://issuer.example/authorize")
                .tokenUri("https://issuer.example/token")
                .jwkSetUri("https://issuer.example/jwks")
                .issuerUri("https://issuer.example")
                .scope("openid")
                .providerConfigurationMetadata(Map.of("revocation_endpoint", uri))
                .build();
    }

    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger redirects = new AtomicInteger();
        final CopyOnWriteArrayList<String> auth = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
        volatile int status;

        Endpoint(int status) throws Exception {
            this.status = status;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/revoke",
                    exchange -> {
                        calls.incrementAndGet();
                        auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                        bodies.add(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        exchange.getResponseHeaders()
                                .set("Location", uri().replace("/revoke", "/redirect"));
                        exchange.sendResponseHeaders(this.status, -1);
                        exchange.close();
                    });
            server.createContext(
                    "/redirect",
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
}
