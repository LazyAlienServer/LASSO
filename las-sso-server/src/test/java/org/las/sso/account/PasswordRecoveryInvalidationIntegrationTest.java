package org.las.sso.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.las.sso.admin.AdminRecoveryCredentialService;
import org.las.sso.maintenance.AdminBootstrapService;
import org.las.sso.maintenance.AdminPasswordResetService;
import org.las.sso.security.PostgresRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        properties = {"las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class PasswordRecoveryInvalidationIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService registration;
    @Autowired PasswordRecoveryService recovery;
    @Autowired PasswordChangeService passwords;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired AdminBootstrapService bootstrap;
    @Autowired AdminPasswordResetService maintenance;
    @Autowired AdminRecoveryCredentialService adminRecovery;
    @Autowired PostgresRateLimiter rateLimiter;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void ordinaryPasswordChangeInvalidatesAnAlreadyIssuedRecoverySession() {
        var fixture = fixture("Change");
        var session = start(fixture);

        passwords.changePassword(
                fixture.userId(), false, fixture.password(), "new owner password phrase");

        assertExpiredWithoutChangingPassword(fixture, session, "new owner password phrase");
        assertThat(liveRecoverySessions(fixture.userId())).isZero();
    }

    @Test
    void requiredPasswordChangeAlsoInvalidatesAnAlreadyIssuedRecoverySession() {
        var fixture = fixture("Required");
        var session = start(fixture);
        // Existing temporary credentials use this same branch. The fixture changes only setup
        // state.
        jdbc.sql(
                        """
                UPDATE password_credentials SET state = 'PASSWORD_CHANGE_REQUIRED',
                    temporary_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 day'
                 WHERE user_id = :userId
                """)
                .param("userId", fixture.userId())
                .update();

        passwords.changePassword(
                fixture.userId(), true, fixture.password(), "new required password phrase");

        assertExpiredWithoutChangingPassword(fixture, session, "new required password phrase");
        assertThat(liveRecoverySessions(fixture.userId())).isZero();
    }

    @Test
    void rejectedPasswordChangeDoesNotInvalidateAValidRecoverySession() {
        var fixture = fixture("Rejected");
        var session = start(fixture);

        assertThatThrownBy(
                        () ->
                                passwords.changePassword(
                                        fixture.userId(),
                                        false,
                                        "incorrect current password",
                                        "new rejected password phrase"))
                .isInstanceOf(PasswordChangeException.class);

        assertThat(liveRecoverySessions(fixture.userId())).isEqualTo(1);
        recovery.complete(session.token(), "valid recovered password phrase", UUID.randomUUID());
        assertThat(
                        encoder.matches(
                                "valid recovered password phrase", passwordHash(fixture.userId())))
                .isTrue();
        assertThat(liveRecoverySessions(fixture.userId())).isZero();
    }

    @Test
    void failedTransactionRestoresPasswordRecoveryStateAndSecurityEvidence() {
        var fixture = fixture("Rollback");
        var session = start(fixture);
        String originalHash = passwordHash(fixture.userId());
        long originalVersion = credentialsVersion(fixture.userId());
        long eventsBefore = passwordChangedEvents(fixture.userId());
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            passwords.changePassword(
                                                    fixture.userId(),
                                                    false,
                                                    fixture.password(),
                                                    "rollback owner password phrase");
                                            assertThat(liveRecoverySessions(fixture.userId()))
                                                    .isZero();
                                            throw new IllegalStateException(
                                                    "Fixture failure before transaction commit");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(passwordHash(fixture.userId())).isEqualTo(originalHash);
        assertThat(credentialsVersion(fixture.userId())).isEqualTo(originalVersion);
        assertThat(liveRecoverySessions(fixture.userId())).isEqualTo(1);
        assertThat(passwordChangedEvents(fixture.userId())).isEqualTo(eventsBefore);
        recovery.complete(session.token(), "rollback valid recovery phrase", UUID.randomUUID());
        assertThat(
                        encoder.matches(
                                "rollback valid recovery phrase", passwordHash(fixture.userId())))
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(PasswordRecoveryService.CredentialType.class)
    void passwordChangeInvalidatesBothRecoveryCredentialTypes(
            PasswordRecoveryService.CredentialType type) {
        var fixture = fixture("Types");
        String credential = fixture.code();
        if (type == PasswordRecoveryService.CredentialType.ADMIN_ISSUED) {
            // Issue a genuine administrator recovery credential through its owning service.
            var actor = administratorFixture();
            credential =
                    adminRecovery
                            .issue(
                                    actor.userId(),
                                    null,
                                    fixture.userId(),
                                    "fixture ownership verified",
                                    UUID.randomUUID())
                            .credential();
        }
        var session =
                recovery.start(
                        fixture.username(),
                        credential,
                        type,
                        null,
                        "credential-type-" + fixture.userId(),
                        UUID.randomUUID());

        passwords.changePassword(
                fixture.userId(), false, fixture.password(), "new typed owner password phrase");

        assertExpiredWithoutChangingPassword(fixture, session, "new typed owner password phrase");
        assertThat(liveRecoverySessions(fixture.userId())).isZero();
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void credentialMutationCommitWinsOverWaitingRecoveryCompletion(Mutation mutation)
            throws Exception {
        var fixture = fixture(mutation.name().substring(0, 3));
        if (mutation == Mutation.REQUIRED) requirePasswordChange(fixture);
        if (mutation == Mutation.RESET) markAdministratorForSetup(fixture);
        if (mutation == Mutation.PROMOTE) clearAdministratorForSetup();
        var session = start(fixture);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                int ownerPid = lockUserAndGetPid(fixture.userId());
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        recoveryCompletionInTransaction(
                                                                session, followerPid, entered)));
                                awaitBlockedOnUser(ownerPid, followerPid, entered);
                                applyMutation(mutation, fixture);
                            });

            assertThat(follower.get().get(10, TimeUnit.SECONDS)).isEqualTo("SESSION_EXPIRED");
            assertThat(encoder.matches(mutation.password, passwordHash(fixture.userId()))).isTrue();
            assertThat(liveRecoverySessions(fixture.userId())).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "CHANGE, RECOVERY_CODE",
        "REQUIRED, RECOVERY_CODE",
        "PROMOTE, RECOVERY_CODE",
        "RESET, RECOVERY_CODE",
        "CHANGE, ADMIN_ISSUED",
        "REQUIRED, ADMIN_ISSUED",
        "PROMOTE, ADMIN_ISSUED"
    })
    void credentialMutationCommitWinsOverWaitingRecoveryCreation(
            Mutation mutation, PasswordRecoveryService.CredentialType type) throws Exception {
        var fixture = fixture("New" + mutation.name().substring(0, 2));
        String credential = fixture.code();
        if (type == PasswordRecoveryService.CredentialType.ADMIN_ISSUED) {
            var actor = administratorFixture();
            credential =
                    adminRecovery
                            .issue(
                                    actor.userId(),
                                    null,
                                    fixture.userId(),
                                    "fixture concurrent ownership verified",
                                    UUID.randomUUID())
                            .credential();
        }
        String submittedCredential = credential;
        if (mutation == Mutation.REQUIRED) requirePasswordChange(fixture);
        if (mutation == Mutation.RESET) markAdministratorForSetup(fixture);
        if (mutation == Mutation.PROMOTE) clearAdministratorForSetup();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                int ownerPid = lockUserAndGetPid(fixture.userId());
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () -> {
                                                    try {
                                                        new TransactionTemplate(transactionManager)
                                                                .executeWithoutResult(
                                                                        followerStatus -> {
                                                                            followerPid.set(
                                                                                    backendPid());
                                                                            entered.countDown();
                                                                            recovery.start(
                                                                                    fixture
                                                                                            .username(),
                                                                                    submittedCredential,
                                                                                    type,
                                                                                    null,
                                                                                    "creation-race-"
                                                                                            + fixture
                                                                                                    .userId(),
                                                                                    UUID
                                                                                            .randomUUID());
                                                                        });
                                                        return "CREATED";
                                                    } catch (RecoveryFlowException exception) {
                                                        return exception.code();
                                                    }
                                                }));
                                awaitBlockedOnUser(ownerPid, followerPid, entered);
                                applyMutation(mutation, fixture);
                            });

            assertThat(follower.get().get(10, TimeUnit.SECONDS))
                    .isEqualTo("RECOVERY_CREDENTIAL_INVALID");
            assertThat(encoder.matches(mutation.password, passwordHash(fixture.userId()))).isTrue();
            assertThat(liveRecoverySessions(fixture.userId())).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void recoveryCompletionCommitsBeforeWaitingCredentialMutation(Mutation mutation)
            throws Exception {
        var fixture = fixture("RecoverFirst");
        if (mutation == Mutation.REQUIRED) requirePasswordChange(fixture);
        if (mutation == Mutation.RESET) markAdministratorForSetup(fixture);
        if (mutation == Mutation.PROMOTE) clearAdministratorForSetup();
        var session = start(fixture);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                // The real recovery operation, rather than the fixture, acquires
                                // and retains the locks.
                                recovery.complete(
                                        session.token(),
                                        "first committed recovery phrase",
                                        UUID.randomUUID());
                                int ownerPid = backendPid();
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () -> {
                                                    try {
                                                        new TransactionTemplate(transactionManager)
                                                                .executeWithoutResult(
                                                                        followerStatus -> {
                                                                            followerPid.set(
                                                                                    backendPid());
                                                                            entered.countDown();
                                                                            applyMutation(
                                                                                    mutation,
                                                                                    fixture);
                                                                        });
                                                        return "COMMITTED";
                                                    } catch (PasswordChangeException exception) {
                                                        return exception.code();
                                                    }
                                                }));
                                awaitBlockedOnUser(ownerPid, followerPid, entered);
                            });

            String expected =
                    switch (mutation) {
                        case CHANGE -> "CURRENT_PASSWORD_INVALID";
                        case REQUIRED -> "TEMPORARY_PASSWORD_EXPIRED";
                        case PROMOTE, RESET -> "COMMITTED";
                    };
            assertThat(follower.get().get(10, TimeUnit.SECONDS)).isEqualTo(expected);
            boolean maintenanceSucceeded =
                    mutation == Mutation.PROMOTE || mutation == Mutation.RESET;
            assertThat(
                            encoder.matches(
                                    maintenanceSucceeded
                                            ? mutation.password
                                            : "first committed recovery phrase",
                                    passwordHash(fixture.userId())))
                    .isTrue();
            assertThat(credentialsVersion(fixture.userId()))
                    .isEqualTo(maintenanceSucceeded ? 3 : 2);
            assertThat(liveRecoverySessions(fixture.userId())).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void committedRecoveryCreationIsInvalidatedByWaitingCredentialMutation(Mutation mutation)
            throws Exception {
        var fixture = fixture("StartFirst");
        if (mutation == Mutation.REQUIRED) requirePasswordChange(fixture);
        if (mutation == Mutation.RESET) markAdministratorForSetup(fixture);
        if (mutation == Mutation.PROMOTE) clearAdministratorForSetup();
        var session = new AtomicReference<PasswordRecoveryService.RecoverySession>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<?>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                session.set(start(fixture));
                                int ownerPid = backendPid();
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        new TransactionTemplate(transactionManager)
                                                                .executeWithoutResult(
                                                                        followerStatus -> {
                                                                            followerPid.set(
                                                                                    backendPid());
                                                                            entered.countDown();
                                                                            applyMutation(
                                                                                    mutation,
                                                                                    fixture);
                                                                        })));
                                awaitBlockedOnUser(ownerPid, followerPid, entered);
                            });
            follower.get().get(10, TimeUnit.SECONDS);
            assertExpiredWithoutChangingPassword(fixture, session.get(), mutation.password);
            assertThat(credentialsVersion(fixture.userId())).isEqualTo(2);
            assertThat(liveRecoverySessions(fixture.userId())).isZero();
        }
    }

    @Test
    void concurrentCompletionOfTwoRecoverySessionsCommitsOnlyOnePassword() throws Exception {
        String name = "Two" + UUID.randomUUID().toString().substring(0, 8);
        // Two genuinely issued recovery codes may create separate sessions under the existing
        // policy.
        var account =
                registration.register(name, "Two sessions fixture", "two sessions initial phrase");
        var winning =
                recovery.start(
                        name,
                        account.recoveryCodes().get(0),
                        PasswordRecoveryService.CredentialType.RECOVERY_CODE,
                        null,
                        "two-session-1",
                        UUID.randomUUID());
        var losing =
                recovery.start(
                        name,
                        account.recoveryCodes().get(1),
                        PasswordRecoveryService.CredentialType.RECOVERY_CODE,
                        null,
                        "two-session-2",
                        UUID.randomUUID());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                recovery.complete(
                                        winning.token(),
                                        "winning two sessions phrase",
                                        UUID.randomUUID());
                                int ownerPid = backendPid();
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        recoveryCompletionInTransaction(
                                                                losing, followerPid, entered)));
                                awaitBlockedOnUser(ownerPid, followerPid, entered);
                            });
            assertThat(follower.get().get(10, TimeUnit.SECONDS)).isEqualTo("SESSION_EXPIRED");
            assertThat(
                            encoder.matches(
                                    "winning two sessions phrase", passwordHash(account.userId())))
                    .isTrue();
            assertThat(credentialsVersion(account.userId())).isEqualTo(2);
            assertThat(liveRecoverySessions(account.userId())).isZero();
        }
    }

    @Test
    void rejectedAdminCredentialAuditCommitsWhileRecoveryTransactionRollsBack() {
        var actor = administratorFixture();
        var fixture = fixture("Audit");
        var issued =
                adminRecovery.issue(
                        actor.userId(),
                        null,
                        fixture.userId(),
                        "fixture verified identity",
                        UUID.randomUUID());
        // A second issuance revokes the first credential through the existing owning service.
        adminRecovery.issue(
                actor.userId(),
                null,
                fixture.userId(),
                "fixture reissued credential",
                UUID.randomUUID());
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                recovery.start(
                                        fixture.username(),
                                        issued.credential(),
                                        PasswordRecoveryService.CredentialType.ADMIN_ISSUED,
                                        null,
                                        "audit-rejection-" + fixture.userId(),
                                        requestId))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("RECOVERY_CREDENTIAL_INVALID");

        assertThat(
                        jdbc.sql(
                                        """
                SELECT result FROM admin_audit_events
                 WHERE action = 'ADMIN_RECOVERY_CREDENTIAL_REJECTED' AND request_id = :id
                """)
                                .param("id", requestId)
                                .query(String.class)
                                .single())
                .isEqualTo("REVOKED");
        assertThat(
                        rateLimiter
                                .current(
                                        "RECOVERY_USER",
                                        fixture.username(),
                                        5,
                                        Duration.ofMinutes(15))
                                .count())
                .isEqualTo(1);
        assertThat(liveRecoverySessions(fixture.userId())).isZero();
        assertThat(credentialsVersion(fixture.userId())).isEqualTo(1);
    }

    @Test
    void recoverySessionExpiryIsRecheckedAfterWaitingForUserLock() throws Exception {
        var fixture = fixture("Expiry");
        var session = start(fixture);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                int ownerPid = lockUserAndGetPid(fixture.userId());
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        recoveryCompletionInTransaction(
                                                                session, followerPid, entered)));
                                awaitBlockedOnUser(ownerPid, followerPid, entered);
                                // Deterministically expire the session while completion is waiting;
                                // no clock sleeps.
                                jdbc.sql(
                                                """
                        UPDATE recovery_sessions
                           SET created_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes 1 second',
                               expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                         WHERE user_id = :id
                        """)
                                        .param("id", fixture.userId())
                                        .update();
                            });
            assertThat(follower.get().get(10, TimeUnit.SECONDS)).isEqualTo("SESSION_EXPIRED");
            assertThat(encoder.matches(fixture.password(), passwordHash(fixture.userId())))
                    .isTrue();
            assertThat(credentialsVersion(fixture.userId())).isEqualTo(1);
        }
    }

    private Fixture fixture(String prefix) {
        String name =
                "Inv"
                        + prefix.substring(0, Math.min(prefix.length(), 10))
                        + UUID.randomUUID().toString().substring(0, 8);
        String password = "initial invalidation password phrase";
        var account = registration.register(name, "Recovery invalidation fixture", password);
        return new Fixture(account.userId(), name, password, account.recoveryCodes().getFirst());
    }

    private PasswordRecoveryService.RecoverySession start(Fixture fixture) {
        return recovery.start(
                fixture.username(),
                fixture.code(),
                PasswordRecoveryService.CredentialType.RECOVERY_CODE,
                null,
                "invalidation-fixture-" + fixture.userId(),
                UUID.randomUUID());
    }

    private void assertExpiredWithoutChangingPassword(
            Fixture fixture,
            PasswordRecoveryService.RecoverySession session,
            String expectedPassword) {
        String before = passwordHash(fixture.userId());
        long versionBefore = credentialsVersion(fixture.userId());
        assertThatThrownBy(
                        () ->
                                recovery.complete(
                                        session.token(),
                                        "stale recovery overwrite phrase",
                                        UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("SESSION_EXPIRED");
        assertThat(passwordHash(fixture.userId())).isEqualTo(before);
        assertThat(credentialsVersion(fixture.userId())).isEqualTo(versionBefore);
        assertThat(encoder.matches(expectedPassword, before)).isTrue();
    }

    private String passwordHash(UUID userId) {
        return jdbc.sql("SELECT password_hash FROM password_credentials WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .single();
    }

    private long credentialsVersion(UUID userId) {
        return jdbc.sql(
                        "SELECT credentials_version FROM password_credentials WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private long liveRecoverySessions(UUID userId) {
        return jdbc.sql(
                        """
                SELECT COUNT(*) FROM recovery_sessions WHERE user_id = :userId
                    AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                """)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private long passwordChangedEvents(UUID userId) {
        return jdbc.sql(
                        "SELECT COUNT(*) FROM security_events WHERE user_id = :userId AND event_type = 'PASSWORD_CHANGED'")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private int lockUserAndGetPid(UUID userId) {
        jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE")
                .param("id", userId)
                .query(UUID.class)
                .single();
        return backendPid();
    }

    private void awaitBlockedOnUser(
            int ownerPid, AtomicInteger followerPid, CountDownLatch entered) {
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                if (jdbc.sql("SELECT :owner = ANY(pg_blocking_pids(:follower))")
                        .param("owner", ownerPid)
                        .param("follower", followerPid.get())
                        .query(Boolean.class)
                        .single()) return;
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
            throw new AssertionError(
                    "Competing credential operation never waited on the owning user transaction");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while observing PostgreSQL lock", exception);
        }
    }

    private String recoveryCompletionInTransaction(
            PasswordRecoveryService.RecoverySession session,
            AtomicInteger pid,
            CountDownLatch entered) {
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                pid.set(backendPid());
                                entered.countDown();
                                recovery.complete(
                                        session.token(),
                                        "competing recovery overwrite phrase",
                                        UUID.randomUUID());
                            });
            return "RECOVERED";
        } catch (RecoveryFlowException exception) {
            return exception.code();
        }
    }

    private void applyMutation(Mutation mutation, Fixture fixture) {
        switch (mutation) {
            case CHANGE ->
                    passwords.changePassword(
                            fixture.userId(), false, fixture.password(), mutation.password);
            case REQUIRED ->
                    passwords.changePassword(
                            fixture.userId(), true, fixture.password(), mutation.password);
            case PROMOTE -> bootstrap.promoteExistingUser(fixture.userId(), mutation.password);
            case RESET -> maintenance.resetPassword(fixture.userId(), mutation.password);
        }
    }

    private void requirePasswordChange(Fixture fixture) {
        jdbc.sql(
                        "UPDATE password_credentials SET state = 'PASSWORD_CHANGE_REQUIRED', "
                                + "temporary_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE user_id = :id")
                .param("id", fixture.userId())
                .update();
    }

    private void clearAdministratorForSetup() {
        jdbc.sql("UPDATE users SET is_sso_admin = false WHERE is_sso_admin = true").update();
    }

    private void markAdministratorForSetup(Fixture fixture) {
        clearAdministratorForSetup();
        jdbc.sql("UPDATE users SET is_sso_admin = true WHERE id = :id")
                .param("id", fixture.userId())
                .update();
    }

    private Fixture administratorFixture() {
        var actor = fixture("Actor");
        markAdministratorForSetup(actor);
        return actor;
    }

    private enum Mutation {
        CHANGE("concurrent ordinary change phrase"),
        REQUIRED("concurrent required change phrase"),
        PROMOTE("concurrent promotion temporary phrase"),
        RESET("concurrent maintenance reset phrase");

        private final String password;

        Mutation(String password) {
            this.password = password;
        }
    }

    private record Fixture(UUID userId, String username, String password, String code) {}
}
