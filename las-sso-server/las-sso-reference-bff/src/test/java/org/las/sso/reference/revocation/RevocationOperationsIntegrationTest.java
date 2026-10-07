package org.las.sso.reference.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.Console;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RevocationOperationsIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    static DriverManagerDataSource dataSource;
    static JdbcClient jdbc;
    static final String TOKEN = "operator-revocation-fixture-token";
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    RevocationTokenCipher cipher;
    JdbcRevocationStore store;

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
        cipher = new RevocationTokenCipher("current-key", KEY);
        store = store(cipher);
    }

    @Test
    void deadReplayIsAuditedOnceAndDeliversAfterRestartWithANewBoundedCampaign() throws Exception {
        try (var endpoint = new Endpoint()) {
            UUID id = enqueue(endpoint.uri());
            dead(id);
            store.replay(id, 0, "operator-a", "receiver repaired");
            assertThat(replayCount(id)).isOne();
            assertThatThrownBy(() -> store.replay(id, 0, "operator-a", "stale retry"))
                    .hasMessage("DEAD_TASK_OR_GENERATION_MISMATCH");
            assertThat(auditCount()).isOne();
            worker(store(cipher), cipher, endpoint.uri()).poll();
            assertThat(endpoint.calls).hasValue(1);
            assertThat(
                            jdbc.sql(
                                            "SELECT completed_at IS NOT NULL AND ciphertext IS NULL FROM reference_revocation.tasks WHERE id=:id")
                                    .param("id", id)
                                    .query(Boolean.class)
                                    .single())
                    .isTrue();
            assertThatThrownBy(() -> store.replay(id, 1, "operator-a", "completed"))
                    .hasMessage("DEAD_TASK_OR_GENERATION_MISMATCH");
        }
    }

    @Test
    void replayRejectsPendingStaleGenerationOrTamperedCiphertextWithoutMutation() {
        UUID id = enqueue("https://issuer.example/revoke");
        assertThatThrownBy(() -> store.replay(id, 0, "operator", "pending"))
                .hasMessage("DEAD_TASK_OR_GENERATION_MISMATCH");
        dead(id);
        assertThatThrownBy(() -> store.replay(id, 1, "operator", "stale"))
                .hasMessage("DEAD_TASK_OR_GENERATION_MISMATCH");
        jdbc.sql(
                        "UPDATE reference_revocation.tasks SET token_type_hint='access_token' WHERE id=:id")
                .param("id", id)
                .update();
        assertThatThrownBy(() -> store.replay(id, 0, "operator", "tampered"))
                .hasMessage("Revocation payload unavailable");
        assertThat(replayCount(id)).isZero();
        assertThat(auditCount()).isZero();
    }

    @Test
    void auditFailureRollsBackReplayRotationAndPurge() {
        UUID id = enqueue("https://issuer.example/revoke");
        dead(id);
        faultAudit();
        try {
            assertThatThrownBy(() -> store.replay(id, 0, "operator", "replay"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(replayCount(id)).isZero();
            jdbc.sql(
                            "UPDATE reference_revocation.tasks SET dead_lettered_at=NULL, completed_at=CURRENT_TIMESTAMP - INTERVAL '40 days', ciphertext=NULL, nonce=NULL WHERE id=:id")
                    .param("id", id)
                    .update();
            assertThatThrownBy(() -> store.rotateKey(next(), "operator", "rotate"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(store(cipher)).isNotNull();
            assertThatThrownBy(
                            () ->
                                    store.purgeCompleted(
                                            Duration.ofDays(30), 10, "operator", "purge"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(taskCount()).isOne();
        } finally {
            removeAuditFault();
        }
    }

    @Test
    void rotationRequiresEveryIncompleteTaskDrainedAndFencesOldProcesses() {
        UUID id = enqueue("https://issuer.example/revoke");
        assertThatThrownBy(() -> store.rotateKey(next(), "operator", "pending"))
                .hasMessage("KEY_DRAIN_REQUIRED");
        dead(id);
        assertThatThrownBy(() -> store.rotateKey(next(), "operator", "dead"))
                .hasMessage("KEY_DRAIN_REQUIRED");
        store.replay(id, 0, "operator", "recovered");
        var claimed = store.claim(RevocationWorker.LEASE).orElseThrow();
        assertThatThrownBy(() -> store.rotateKey(next(), "operator", "leased"))
                .hasMessage("KEY_DRAIN_REQUIRED");
        assertThat(store.complete(claimed)).isTrue();
        store.rotateKey(next(), "operator", "queue drained");
        assertThat(auditCount()).isEqualTo(2);
        assertThatThrownBy(() -> enqueue("https://issuer.example/revoke"))
                .hasMessage("Durable revocation acceptance failed");
        assertThatThrownBy(() -> store.claim(RevocationWorker.LEASE))
                .hasMessage("KEY_CONFIGURATION_CHANGED");
        assertThatThrownBy(() -> store(cipher)).hasMessage("KEY_CONFIGURATION_CHANGED");
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                """
                INSERT INTO reference_revocation.tasks
                    (id, registration_id, issuer, client_id, revocation_uri, token_type_hint, key_id, nonce, ciphertext)
                VALUES (:id, 'legacy', 'https://issuer.example', 'legacy-client', 'https://issuer.example/revoke',
                        'refresh_token', 'current-key', decode(repeat('00',12),'hex'), decode(repeat('00',16),'hex'))
                """)
                                        .param("id", UUID.randomUUID())
                                        .update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        JdbcRevocationStore restarted = store(next());
        byte[] newer = new byte[32];
        newer[0] = 2;
        assertThatThrownBy(
                        () ->
                                restarted.rotateKey(
                                        new RevocationTokenCipher(
                                                "current-key",
                                                Base64.getEncoder().encodeToString(newer)),
                                        "operator",
                                        "reused retired id"))
                .hasMessage("KEY_ID_REUSE_FORBIDDEN");
        new JdbcRevocationQueue(restarted, next(), false)
                .submit(
                        JdbcRevocationIntegrationTest.registration(
                                "https://issuer.example/revoke", "fixture-secret"),
                        TOKEN,
                        "refresh_token");
        assertThat(restarted.claim(RevocationWorker.LEASE)).isPresent();
    }

    @Test
    void sameIdDifferentKeyCannotStartAndNewIdRequiresExplicitRotation() {
        byte[] changed = new byte[32];
        changed[0] = 1;
        assertThatThrownBy(
                        () ->
                                store(
                                        new RevocationTokenCipher(
                                                "current-key",
                                                Base64.getEncoder().encodeToString(changed))))
                .hasMessage("KEY_CONFIGURATION_CHANGED");
        assertThatThrownBy(() -> store(next())).hasMessage("KEY_CONFIGURATION_CHANGED");
        assertThatThrownBy(() -> store.rotateKey(cipher, "operator", "same id"))
                .hasMessage("NEW_KEY_ID_REQUIRED");
        assertThatThrownBy(
                        () ->
                                store.rotateKey(
                                        new RevocationTokenCipher("new-id-same-key", KEY),
                                        "operator",
                                        "same material"))
                .hasMessage("NEW_KEY_MATERIAL_REQUIRED");
    }

    @Test
    void upgradedV1QueueAuthenticatesExistingWorkBeforeCreatingKeyRegistry() {
        UUID id = enqueue("https://issuer.example/revoke");
        jdbc.sql("TRUNCATE reference_revocation.key_state").update();
        assertThatThrownBy(() -> store(next())).hasMessage("KEY_DRAIN_REQUIRED");
        assertThat(store(cipher)).isNotNull();
        jdbc.sql("TRUNCATE reference_revocation.key_state").update();
        jdbc.sql(
                        "UPDATE reference_revocation.tasks SET token_type_hint='access_token' WHERE id=:id")
                .param("id", id)
                .update();
        assertThatThrownBy(() -> store(cipher)).hasMessage("Revocation payload unavailable");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM reference_revocation.key_state")
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void actualV1ToV2MigrationPreservesCiphertextAndMakesOldTaskDeliverable() throws Exception {
        jdbc.sql("CREATE DATABASE revocation_v1_upgrade_fixture").update();
        var upgradeData =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl()
                                .replace(
                                        "/" + POSTGRES.getDatabaseName(),
                                        "/revocation_v1_upgrade_fixture"),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        var upgradeJdbc = JdbcClient.create(upgradeData);
        Flyway.configure()
                .dataSource(upgradeData)
                .defaultSchema("reference_revocation")
                .locations("classpath:revocation-db")
                .target("1")
                .load()
                .migrate();
        try (var endpoint = new Endpoint()) {
            var registration =
                    JdbcRevocationIntegrationTest.registration(endpoint.uri(), "fixture-secret");
            var material =
                    cipher.encrypt(
                            new RevocationTask(
                                    UUID.randomUUID(),
                                    registration.getRegistrationId(),
                                    registration.getProviderDetails().getIssuerUri(),
                                    registration.getClientId(),
                                    endpoint.uri(),
                                    "refresh_token",
                                    null,
                                    null,
                                    null,
                                    null,
                                    0),
                            TOKEN);
            upgradeJdbc
                    .sql(
                            """
                    INSERT INTO reference_revocation.tasks (id, registration_id, issuer, client_id, revocation_uri, token_type_hint, key_id, nonce, ciphertext)
                    VALUES (:id, :registration, :issuer, :client, :uri, :hint, :key, :nonce, :ciphertext)
                    """)
                    .param("id", material.id())
                    .param("registration", material.registrationId())
                    .param("issuer", material.issuer())
                    .param("client", material.clientId())
                    .param("uri", material.uri())
                    .param("hint", material.hint())
                    .param("key", material.keyId())
                    .param("nonce", material.nonce())
                    .param("ciphertext", material.ciphertext())
                    .update();
            Flyway.configure()
                    .dataSource(upgradeData)
                    .defaultSchema("reference_revocation")
                    .locations("classpath:revocation-db")
                    .load()
                    .migrate();
            var upgraded =
                    new JdbcRevocationStore(
                            upgradeJdbc, new DataSourceTransactionManager(upgradeData), cipher);
            assertThat(
                            upgradeJdbc
                                    .sql("SELECT replay_count FROM reference_revocation.tasks")
                                    .query(Integer.class)
                                    .single())
                    .isZero();
            worker(upgraded, cipher, endpoint.uri()).poll();
            assertThat(endpoint.calls).hasValue(1);
            assertThat(
                            upgradeJdbc
                                    .sql(
                                            "SELECT completed_at IS NOT NULL AND ciphertext IS NULL FROM reference_revocation.tasks")
                                    .query(Boolean.class)
                                    .single())
                    .isTrue();
        }
    }

    @Test
    void workerWithWrongKeyCannotConsumeAttemptsOrClaimAnyWork() {
        UUID id = enqueue("https://issuer.example/revoke");
        worker(store, next(), "https://issuer.example/revoke").poll();
        assertThat(
                        jdbc.sql(
                                        "SELECT attempt_count FROM reference_revocation.tasks WHERE id=:id")
                                .param("id", id)
                                .query(Integer.class)
                                .single())
                .isZero();
        assertThat(store.claim(RevocationWorker.LEASE)).isPresent();
    }

    @Test
    void completedRetentionIsBoundedAndNeverRemovesDeadPendingOrFreshReceipts() {
        for (int index = 0; index < 4; index++) {
            enqueue("https://issuer.example/revoke");
            var task = store.claim(RevocationWorker.LEASE).orElseThrow();
            store.complete(task);
        }
        jdbc.sql(
                        "UPDATE reference_revocation.tasks SET completed_at=CURRENT_TIMESTAMP - INTERVAL '40 days'")
                .update();
        UUID fresh = enqueue("https://issuer.example/revoke");
        store.complete(store.claim(RevocationWorker.LEASE).orElseThrow());
        UUID pending = enqueue("https://issuer.example/revoke");
        UUID dead = enqueue("https://issuer.example/revoke");
        dead(dead);
        assertThat(store.purgeCompleted(Duration.ofDays(30), 2, "operator", "retention"))
                .isEqualTo(2);
        assertThat(taskCount()).isEqualTo(5);
        assertThat(store.purgeCompleted(Duration.ofDays(30), 2, "operator", "retention"))
                .isEqualTo(2);
        assertThat(store.purgeCompleted(Duration.ofDays(30), 2, "operator", "retention")).isZero();
        assertThat(jdbc.sql("SELECT id FROM reference_revocation.tasks").query(UUID.class).list())
                .containsExactlyInAnyOrder(fresh, pending, dead);
        assertThat(auditCount()).isEqualTo(2);
    }

    @Test
    void concurrentReplayHasExactlyOneCommittedGeneration() throws Exception {
        UUID id = enqueue("https://issuer.example/revoke");
        dead(id);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CyclicBarrier(2);
            java.util.concurrent.Callable<Boolean> replay =
                    () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            store(cipher).replay(id, 0, "operator", "concurrent replay");
                            return true;
                        } catch (IllegalStateException exception) {
                            return false;
                        }
                    };
            var a = executor.submit(replay);
            var b = executor.submit(replay);
            assertThat(
                            (a.get(10, TimeUnit.SECONDS) ? 1 : 0)
                                    + (b.get(10, TimeUnit.SECONDS) ? 1 : 0))
                    .isOne();
        }
        assertThat(replayCount(id)).isOne();
        assertThat(auditCount()).isOne();
    }

    @Test
    void rotationAndAcceptanceCannotCommitOldKeyWorkAfterRotation() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CyclicBarrier(2);
            var accepted =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                try {
                                    enqueue("https://issuer.example/revoke");
                                    return true;
                                } catch (IllegalStateException error) {
                                    return false;
                                }
                            });
            var rotated =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                try {
                                    store.rotateKey(next(), "operator", "concurrent rotation");
                                    return true;
                                } catch (IllegalStateException error) {
                                    return false;
                                }
                            });
            boolean a = accepted.get(10, TimeUnit.SECONDS), b = rotated.get(10, TimeUnit.SECONDS);
            assertThat(a ^ b).isTrue();
            assertThat(taskCount()).isEqualTo(a ? 1 : 0);
        }
    }

    @Test
    void offlineCommandRefusesWebWorkerConsoleOrCancelledConfirmationAndRunsReplay() {
        UUID id = enqueue("https://issuer.example/revoke");
        dead(id);
        var properties =
                new RevocationProperties(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword(),
                        "current-key",
                        KEY,
                        false,
                        Duration.ofDays(30),
                        100);
        var environment =
                new MockEnvironment()
                        .withProperty("spring.main.web-application-type", "none")
                        .withProperty("reference.revocation.worker-enabled", "false");
        var args =
                new DefaultApplicationArguments(
                        "--command=replay",
                        "--actor=operator",
                        "--reason=receiver repaired",
                        "--expected-key-id=current-key",
                        "--task-id=" + id,
                        "--expected-replay-count=0");
        var command =
                new RevocationMaintenance.Command(
                        store, cipher, properties, environment, () -> null);
        assertThatThrownBy(() -> command.run(args)).hasMessage("INTERACTIVE_CONSOLE_REQUIRED");
        var console = mock(Console.class);
        var confirmed =
                new RevocationMaintenance.Command(
                        store, cipher, properties, environment, () -> console);
        assertThatThrownBy(() -> confirmed.run(args))
                .hasMessage("REVOCATION_MAINTENANCE_NOT_CONFIRMED");
        when(console.readLine("Offline reference revocation %s. Type YES to confirm: ", "replay"))
                .thenReturn("YES");
        confirmed.run(args);
        assertThat(replayCount(id)).isOne();
        environment.withProperty("reference.revocation.worker-enabled", "true");
        assertThatThrownBy(() -> confirmed.run(args))
                .hasMessage("REVOCATION_MAINTENANCE_REQUIRES_OFFLINE_MODE");
        environment.withProperty("reference.revocation.worker-enabled", "false");
        assertThatThrownBy(
                        () ->
                                confirmed.run(
                                        new DefaultApplicationArguments(
                                                "--command=rotate-key",
                                                "--reference.revocation.next-key-base64=fixture-secret")))
                .hasMessage("REVOCATION_SECRET_OPTION_NOT_ALLOWED");
        assertThatThrownBy(
                        () ->
                                confirmed.run(
                                        new DefaultApplicationArguments(
                                                "--command=replay",
                                                "--actor=operator",
                                                "--reason=fixture",
                                                "--expected-key-id=wrong")))
                .hasMessage("REVOCATION_EXPECTED_KEY_MISMATCH");
    }

    private JdbcRevocationStore store(RevocationTokenCipher key) {
        return new JdbcRevocationStore(jdbc, new DataSourceTransactionManager(dataSource), key);
    }

    private RevocationTokenCipher next() {
        byte[] key = new byte[32];
        key[0] = 1;
        return new RevocationTokenCipher("next-key", Base64.getEncoder().encodeToString(key));
    }

    private UUID enqueue(String uri) {
        var before = jdbc.sql("SELECT id FROM reference_revocation.tasks").query(UUID.class).list();
        new JdbcRevocationQueue(store, cipher, true)
                .submit(
                        JdbcRevocationIntegrationTest.registration(uri, "fixture-secret"),
                        TOKEN,
                        "refresh_token");
        return jdbc
                .sql("SELECT id FROM reference_revocation.tasks")
                .query(UUID.class)
                .list()
                .stream()
                .filter(id -> !before.contains(id))
                .findFirst()
                .orElseThrow();
    }

    private void dead(UUID id) {
        jdbc.sql(
                        "UPDATE reference_revocation.tasks SET attempt_count=8,dead_lettered_at=CURRENT_TIMESTAMP,last_error_code='HTTP_503' WHERE id=:id")
                .param("id", id)
                .update();
    }

    private int replayCount(UUID id) {
        return jdbc.sql("SELECT replay_count FROM reference_revocation.tasks WHERE id=:id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private long auditCount() {
        return jdbc.sql("SELECT count(*) FROM reference_revocation.operator_audit")
                .query(Long.class)
                .single();
    }

    private long taskCount() {
        return jdbc.sql("SELECT count(*) FROM reference_revocation.tasks")
                .query(Long.class)
                .single();
    }

    private RevocationWorker worker(
            JdbcRevocationStore selectedStore, RevocationTokenCipher key, String uri) {
        return new RevocationWorker(
                selectedStore,
                key,
                new InMemoryClientRegistrationRepository(
                        JdbcRevocationIntegrationTest.registration(uri, "fixture-secret")),
                true);
    }

    private void faultAudit() {
        jdbc.sql(
                        "CREATE FUNCTION reference_revocation.reject_audit_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture audit write unavailable'; END $$")
                .update();
        jdbc.sql(
                        "CREATE TRIGGER audit_fixture BEFORE INSERT ON reference_revocation.operator_audit FOR EACH ROW EXECUTE FUNCTION reference_revocation.reject_audit_fixture()")
                .update();
    }

    private void removeAuditFault() {
        jdbc.sql("DROP TRIGGER audit_fixture ON reference_revocation.operator_audit").update();
        jdbc.sql("DROP FUNCTION reference_revocation.reject_audit_fixture()").update();
    }

    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();

        Endpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/revoke",
                    exchange -> {
                        exchange.getRequestBody().readAllBytes();
                        calls.incrementAndGet();
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                    });
            server.start();
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/revoke";
        }

        public void close() {
            server.stop(0);
        }
    }
}
