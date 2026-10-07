package org.las.sso.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.las.sso.account.AccountDeletionConflictException;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.account.DeletionRequestService;
import org.las.sso.account.PasswordPolicyException;
import org.las.sso.account.PasswordRecoveryService;
import org.las.sso.admin.AdminDeletionRequestService;
import org.las.sso.admin.AdminRecoveryCredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Spring-proxied maintenance/account services against a real PostgreSQL lifecycle fixture. */
@SpringBootTest(
        properties = {"las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class AdminPromotionDeletionIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService registration;
    @Autowired AdminBootstrapService bootstrap;
    @Autowired DeletionRequestService deletions;
    @Autowired AdminDeletionRequestService adminDeletions;
    @Autowired PasswordRecoveryService recovery;
    @Autowired AdminRecoveryCredentialService adminRecovery;
    @Autowired RegisteredClientRepository clients;
    @Autowired OAuth2AuthorizationService authorizations;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void noExistingAdministratorInIsolatedFixture() {
        jdbc.sql("UPDATE users SET is_sso_admin = false WHERE is_sso_admin = true").update();
    }

    @ParameterizedTest
    @EnumSource(EffectiveDeletion.class)
    void effectiveDeletionBlocksPromotionBeforeAnyRoleCredentialOrSecurityMutation(
            EffectiveDeletion status) {
        var fixture = fixture(status);
        var before = snapshot(fixture.userId());

        assertThatThrownBy(
                        () ->
                                bootstrap.promoteExistingUser(
                                        fixture.userId(), "s4 temporary admin password phrase"))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("DELETION_CANCELLATION_REQUIRED");

        assertThat(snapshot(fixture.userId())).isEqualTo(before);
        assertThat(encoder.matches(fixture.password(), hash(fixture.userId()))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(EffectiveDeletion.class)
    void overdueButNotTerminalDeletionStillBlocksPromotion(EffectiveDeletion status) {
        var fixture = fixture(status);
        if (status == EffectiveDeletion.PENDING) due(fixture);
        else
            jdbc.sql(
                            "UPDATE account_deletion_requests SET hold_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = :id")
                    .param("id", fixture.requestId())
                    .update();
        var before = snapshot(fixture.userId());

        assertThatThrownBy(
                        () ->
                                bootstrap.promoteExistingUser(
                                        fixture.userId(), "s4 temporary admin password phrase"))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("DELETION_CANCELLATION_REQUIRED");

        assertThat(snapshot(fixture.userId())).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(EffectiveDeletion.class)
    void ownerCancellationThenRetryPromotesWithoutRewritingTerminalRequest(
            EffectiveDeletion status) {
        var fixture = fixture(status);
        assertThatThrownBy(
                        () ->
                                bootstrap.promoteExistingUser(
                                        fixture.userId(), "s4 temporary admin password phrase"))
                .isInstanceOf(AdminBootstrapException.class);
        deletions.cancel(fixture.userId(), UUID.randomUUID());
        var cancelled = requestSnapshot(fixture.requestId());
        long version = version(fixture.userId());

        var result =
                bootstrap.promoteExistingUser(
                        fixture.userId(), "s4 temporary admin password phrase");

        assertThat(result.userId()).isEqualTo(fixture.userId());
        assertThat(requestSnapshot(fixture.requestId())).isEqualTo(cancelled);
        assertSuccessfulPromotion(fixture, version + 1);
    }

    @Test
    void noDeletionRequestAllowsNormalPromotionAndExistingAdminPreventsAnotherPromotion() {
        var first = fixture(null);
        var second = fixture(null);
        long version = version(first.userId());
        bootstrap.promoteExistingUser(first.userId(), "s4 temporary admin password phrase");
        assertSuccessfulPromotion(first, version + 1);
        var secondBefore = snapshot(second.userId());

        assertThatThrownBy(
                        () ->
                                bootstrap.promoteExistingUser(
                                        second.userId(), "s4 second temporary password phrase"))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("ADMIN_ALREADY_EXISTS");
        assertThat(snapshot(second.userId())).isEqualTo(secondBefore);
    }

    @Test
    void cancelledHistoryDoesNotHideANewerPendingRequest() {
        var fixture = fixture(EffectiveDeletion.PENDING);
        deletions.cancel(fixture.userId(), UUID.randomUUID());
        deletions.request(fixture.userId(), UUID.randomUUID());
        var before = snapshot(fixture.userId());

        assertThatThrownBy(
                        () ->
                                bootstrap.promoteExistingUser(
                                        fixture.userId(), "s4 temporary admin password phrase"))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("DELETION_CANCELLATION_REQUIRED");
        assertThat(snapshot(fixture.userId())).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(NonActive.class)
    void nonActiveAccountCannotBePromotedAndRejectedStateIsUnchanged(NonActive state) {
        var fixture = fixture(null);
        jdbc.sql(
                        """
                UPDATE users SET account_status = CAST(:state AS account_status),
                    deleted_at = CASE WHEN :state = 'DELETED' THEN CURRENT_TIMESTAMP ELSE NULL END
                 WHERE id = :id
                """)
                .param("state", state.name())
                .param("id", fixture.userId())
                .update();
        var before = snapshot(fixture.userId());

        assertThatThrownBy(
                        () ->
                                bootstrap.promoteExistingUser(
                                        fixture.userId(), "s4 temporary admin password phrase"))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("USER_NOT_FOUND");
        assertThat(snapshot(fixture.userId())).isEqualTo(before);
    }

    @Test
    void unknownUserIsRejectedWithoutNewAuditOrRole() {
        long audits =
                jdbc.sql("SELECT COUNT(*) FROM admin_audit_events").query(Long.class).single();
        assertThatThrownBy(
                        () ->
                                bootstrap.promoteExistingUser(
                                        UUID.randomUUID(), "s4 temporary admin password phrase"))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("USER_NOT_FOUND");
        assertThat(
                        jdbc.sql("SELECT COUNT(*) FROM users WHERE is_sso_admin")
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM admin_audit_events").query(Long.class).single())
                .isEqualTo(audits);
    }

    @Test
    void effectiveDeletionRejectionOccursBeforeTemporaryPasswordPolicyOrHashing() {
        var fixture = fixture(EffectiveDeletion.PENDING);
        var before = snapshot(fixture.userId());
        assertThatThrownBy(() -> bootstrap.promoteExistingUser(fixture.userId(), "short"))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("DELETION_CANCELLATION_REQUIRED");
        assertThat(snapshot(fixture.userId())).isEqualTo(before);
        deletions.cancel(fixture.userId(), UUID.randomUUID());
        var cancelled = snapshot(fixture.userId());
        assertThatThrownBy(() -> bootstrap.promoteExistingUser(fixture.userId(), "short"))
                .isInstanceOf(PasswordPolicyException.class);
        assertThat(snapshot(fixture.userId())).isEqualTo(cancelled);
    }

    @Test
    void deletionRequestCommitWinsOverPromotionThatAlreadyHoldsMaintenanceLock() throws Exception {
        var fixture = fixture(null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            var afterRequest = new AtomicReference<List<List<String>>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                int owner = lockUser(fixture.userId());
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        inTransaction(
                                                                followerPid,
                                                                entered,
                                                                () ->
                                                                        bootstrap
                                                                                .promoteExistingUser(
                                                                                        fixture
                                                                                                .userId(),
                                                                                        "s4 temporary admin password phrase"))));
                                awaitBlocking(owner, followerPid, entered);
                                deletions.request(fixture.userId(), UUID.randomUUID());
                                afterRequest.set(snapshot(fixture.userId()));
                            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS))
                    .isEqualTo("DELETION_CANCELLATION_REQUIRED");
            assertThat(snapshot(fixture.userId())).isEqualTo(afterRequest.get());
        }
    }

    @Test
    void promotionCommitMakesWaitingDeletionRequestFailWithoutCreatingRequest() throws Exception {
        var fixture = fixture(null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                bootstrap.promoteExistingUser(
                                        fixture.userId(), "s4 temporary admin password phrase");
                                int owner = pid();
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        inTransaction(
                                                                followerPid,
                                                                entered,
                                                                () ->
                                                                        deletions.request(
                                                                                fixture.userId(),
                                                                                UUID
                                                                                        .randomUUID()))));
                                awaitBlocking(owner, followerPid, entered);
                            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS))
                    .isEqualTo("ADMIN_DELETION_FORBIDDEN");
        }
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM account_deletion_requests WHERE user_id = :id")
                                .param("id", fixture.userId())
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql("SELECT is_sso_admin FROM users WHERE id = :id")
                                .param("id", fixture.userId())
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(EffectiveDeletion.class)
    void cancellationCommitMakesWaitingPromotionEligible(EffectiveDeletion status)
            throws Exception {
        var fixture = fixture(status);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            var cancelled = new AtomicReference<String>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            tx -> {
                                int owner = lockUser(fixture.userId());
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        inTransaction(
                                                                followerPid,
                                                                entered,
                                                                () ->
                                                                        bootstrap
                                                                                .promoteExistingUser(
                                                                                        fixture
                                                                                                .userId(),
                                                                                        "s4 temporary admin password phrase"))));
                                awaitBlocking(owner, followerPid, entered);
                                deletions.cancel(fixture.userId(), UUID.randomUUID());
                                cancelled.set(requestSnapshot(fixture.requestId()));
                            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS)).isEqualTo("SUCCESS");
            assertThat(requestSnapshot(fixture.requestId())).isEqualTo(cancelled.get());
        }
        assertThat(
                        jdbc.sql("SELECT is_sso_admin FROM users WHERE id = :id")
                                .param("id", fixture.userId())
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    @Test
    void deletionExecutionCommitMakesWaitingPromotionRejectDeletedAccount() throws Exception {
        var fixture = fixture(EffectiveDeletion.PENDING);
        due(fixture);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            tx -> {
                                int owner = lockUser(fixture.userId());
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        inTransaction(
                                                                followerPid,
                                                                entered,
                                                                () ->
                                                                        bootstrap
                                                                                .promoteExistingUser(
                                                                                        fixture
                                                                                                .userId(),
                                                                                        "s4 temporary admin password phrase"))));
                                awaitBlocking(owner, followerPid, entered);
                                deletions.execute(fixture.requestId(), fixture.userId());
                            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS)).isEqualTo("USER_NOT_FOUND");
        }
        assertThat(
                        jdbc.sql("SELECT account_status::text FROM users WHERE id = :id")
                                .param("id", fixture.userId())
                                .query(String.class)
                                .single())
                .isEqualTo("DELETED");
        assertThat(
                        jdbc.sql("SELECT is_sso_admin FROM users WHERE id = :id")
                                .param("id", fixture.userId())
                                .query(Boolean.class)
                                .single())
                .isFalse();
    }

    @Test
    void pendingRequestCreatedThenHeldWhilePromotionWaitsIsStillRejected() throws Exception {
        var fixture = fixture(null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            tx -> {
                                int owner = lockUser(fixture.userId());
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        inTransaction(
                                                                followerPid,
                                                                entered,
                                                                () ->
                                                                        bootstrap
                                                                                .promoteExistingUser(
                                                                                        fixture
                                                                                                .userId(),
                                                                                        "s4 temporary admin password phrase"))));
                                awaitBlocking(owner, followerPid, entered);
                                deletions.request(fixture.userId(), UUID.randomUUID());
                                UUID requestId =
                                        jdbc.sql(
                                                        "SELECT id FROM account_deletion_requests WHERE user_id = :id AND status = 'PENDING'")
                                                .param("id", fixture.userId())
                                                .query(UUID.class)
                                                .single();
                                adminDeletions.hold(
                                        fixture.actorId(),
                                        null,
                                        requestId,
                                        "fixture dispute",
                                        "核验中",
                                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                                        UUID.randomUUID());
                            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS))
                    .isEqualTo("DELETION_CANCELLATION_REQUIRED");
        }
        assertThat(deletions.status(fixture.userId()).status()).isEqualTo("HELD");
    }

    @Test
    void promotionWaitingForUserDoesNotOwnAnAssociatedDeletionRequest() throws Exception {
        var fixture = fixture(EffectiveDeletion.PENDING);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            tx -> {
                                int owner = lockUser(fixture.userId());
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        inTransaction(
                                                                followerPid,
                                                                entered,
                                                                () ->
                                                                        bootstrap
                                                                                .promoteExistingUser(
                                                                                        fixture
                                                                                                .userId(),
                                                                                        "s4 temporary admin password phrase"))));
                                awaitBlocking(owner, followerPid, entered);
                                try {
                                    assertThat(
                                                    executor.submit(
                                                                    () ->
                                                                            new TransactionTemplate(
                                                                                            transactionManager)
                                                                                    .execute(
                                                                                            probe ->
                                                                                                    jdbc.sql(
                                                                                                                    "SELECT id FROM account_deletion_requests WHERE id = :id FOR UPDATE SKIP LOCKED")
                                                                                                            .param(
                                                                                                                    "id",
                                                                                                                    fixture
                                                                                                                            .requestId())
                                                                                                            .query(
                                                                                                                    UUID.class)
                                                                                                            .optional()
                                                                                                            .isPresent()))
                                                            .get(5, TimeUnit.SECONDS))
                                            .isTrue();
                                } catch (Exception exception) {
                                    throw new AssertionError(
                                            "Deletion lock probe failed", exception);
                                }
                            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS))
                    .isEqualTo("DELETION_CANCELLATION_REQUIRED");
        }
    }

    @Test
    void maintenanceBootstrapRacesPromoteOnlyOneEligibleAccount() throws Exception {
        var first = fixture(null);
        var second = fixture(null);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return promotionOutcome(first.userId());
                            });
            var b =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return promotionOutcome(second.userId());
                            });
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "ADMIN_ALREADY_EXISTS");
        }
        assertThat(
                        jdbc.sql("SELECT COUNT(*) FROM users WHERE is_sso_admin")
                                .query(Long.class)
                                .single())
                .isOne();
    }

    @Test
    void promotionFailureBeforeCommitRollsBackRoleHashVersionsSecurityAndAudit() {
        var fixture = fixture(EffectiveDeletion.PENDING);
        deletions.cancel(fixture.userId(), UUID.randomUUID());
        var before = snapshot(fixture.userId());

        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactionManager)
                                        .executeWithoutResult(
                                                tx -> {
                                                    bootstrap.promoteExistingUser(
                                                            fixture.userId(),
                                                            "s4 temporary admin password phrase");
                                                    throw new IllegalStateException(
                                                            "Fixture failure before commit");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(snapshot(fixture.userId())).isEqualTo(before);
        assertThat(promotionOutcome(fixture.userId())).isEqualTo("SUCCESS");
    }

    @Test
    void actualPromotionAuditWriteFailureRollsBackAllEarlierMutationAndAllowsRetry() {
        var fixture = fixture(EffectiveDeletion.HELD);
        deletions.cancel(fixture.userId(), UUID.randomUUID());
        var before = snapshot(fixture.userId());
        jdbc.sql(
                        """
                CREATE FUNCTION s4_fixture_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.action = 'ADMIN_BOOTSTRAP_PROMOTE' THEN
                        RAISE EXCEPTION 's4_fixture_failure_before_commit';
                    END IF;
                    RETURN NEW;
                END; $$
                """)
                .update();
        try {
            jdbc.sql(
                            "CREATE TRIGGER s4_fixture_audit_failure BEFORE INSERT ON admin_audit_events "
                                    + "FOR EACH ROW EXECUTE FUNCTION s4_fixture_audit_failure()")
                    .update();
            try {
                assertThatThrownBy(
                                () ->
                                        bootstrap.promoteExistingUser(
                                                fixture.userId(),
                                                "s4 temporary admin password phrase"))
                        .isInstanceOf(DataAccessException.class);
            } finally {
                jdbc.sql("DROP TRIGGER s4_fixture_audit_failure ON admin_audit_events").update();
            }
        } finally {
            jdbc.sql("DROP FUNCTION s4_fixture_audit_failure()").update();
        }
        assertThat(snapshot(fixture.userId())).isEqualTo(before);
        assertThat(promotionOutcome(fixture.userId())).isEqualTo("SUCCESS");
    }

    @Test
    void successfulPromotionWaitsForCancellationCommitBeforeChangingRoleOrCredentials()
            throws Exception {
        var fixture = fixture(EffectiveDeletion.HELD);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            tx -> {
                                deletions.cancel(fixture.userId(), UUID.randomUUID());
                                int owner = pid();
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        inTransaction(
                                                                followerPid,
                                                                entered,
                                                                () ->
                                                                        bootstrap
                                                                                .promoteExistingUser(
                                                                                        fixture
                                                                                                .userId(),
                                                                                        "s4 temporary admin password phrase"))));
                                awaitBlocking(owner, followerPid, entered);
                                assertThat(
                                                jdbc.sql(
                                                                "SELECT is_sso_admin FROM users WHERE id = :id")
                                                        .param("id", fixture.userId())
                                                        .query(Boolean.class)
                                                        .single())
                                        .isFalse();
                                assertThat(
                                                encoder.matches(
                                                        fixture.password(), hash(fixture.userId())))
                                        .isTrue();
                            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS)).isEqualTo("SUCCESS");
        }
        assertThat(
                        jdbc.sql("SELECT is_sso_admin FROM users WHERE id = :id")
                                .param("id", fixture.userId())
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    @Test
    void releasingHoldDoesNotCancelDeletionOrPermitPromotion() {
        var fixture = fixture(EffectiveDeletion.HELD);
        adminDeletions.release(
                fixture.actorId(),
                null,
                fixture.requestId(),
                "fixture dispute resolved",
                UUID.randomUUID());
        assertThat(deletions.status(fixture.userId()).status()).isEqualTo("PENDING");
        var before = snapshot(fixture.userId());

        assertThatThrownBy(
                        () ->
                                bootstrap.promoteExistingUser(
                                        fixture.userId(), "s4 temporary admin password phrase"))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("DELETION_CANCELLATION_REQUIRED");
        assertThat(snapshot(fixture.userId())).isEqualTo(before);
    }

    @Test
    void accountFrozenWhilePromotionWaitsIsRecheckedAndNotPromoted() throws Exception {
        var fixture = fixture(null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            var frozen = new AtomicReference<List<List<String>>>();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            tx -> {
                                int owner = lockUser(fixture.userId());
                                var followerPid = new AtomicInteger();
                                var entered = new CountDownLatch(1);
                                follower.set(
                                        executor.submit(
                                                () ->
                                                        inTransaction(
                                                                followerPid,
                                                                entered,
                                                                () ->
                                                                        bootstrap
                                                                                .promoteExistingUser(
                                                                                        fixture
                                                                                                .userId(),
                                                                                        "s4 temporary admin password phrase"))));
                                awaitBlocking(owner, followerPid, entered);
                                // Isolated fixture mutation exercises SELECT FOR UPDATE's
                                // current-state recheck after waiting.
                                jdbc.sql(
                                                "UPDATE users SET account_status = 'FROZEN', frozen_at = CURRENT_TIMESTAMP WHERE id = :id")
                                        .param("id", fixture.userId())
                                        .update();
                                frozen.set(snapshot(fixture.userId()));
                            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS)).isEqualTo("USER_NOT_FOUND");
            assertThat(snapshot(fixture.userId())).isEqualTo(frozen.get());
        }
    }

    private Fixture fixture(EffectiveDeletion status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String password = "s4 initial owner password phrase";
        var account = registration.register("S4Owner" + suffix, "Promotion fixture", password);
        var actor =
                registration.register(
                        "S4Actor" + suffix, "Promotion fixture actor", "s4 actor password phrase");
        recovery.start(
                "S4Owner" + suffix,
                account.recoveryCodes().getFirst(),
                PasswordRecoveryService.CredentialType.RECOVERY_CODE,
                null,
                "s4-network-" + suffix,
                UUID.randomUUID());
        adminRecovery.issue(
                actor.userId(),
                null,
                account.userId(),
                "fixture identity verified",
                UUID.randomUUID());
        UUID requestId = null;
        if (status != null) {
            deletions.request(account.userId(), UUID.randomUUID());
            requestId =
                    jdbc.sql(
                                    "SELECT id FROM account_deletion_requests WHERE user_id = :id AND status = 'PENDING'")
                            .param("id", account.userId())
                            .query(UUID.class)
                            .single();
        }
        if (status == EffectiveDeletion.HELD) {
            adminDeletions.hold(
                    actor.userId(),
                    null,
                    requestId,
                    "fixture dispute",
                    "核验中",
                    OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                    UUID.randomUUID());
        }
        // Establish post-request restricted-session/authorization fixtures so rejection must
        // preserve them too.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String epoch =
                jdbc.sql("SELECT security_epoch FROM system_security_state WHERE id = 1")
                        .query(String.class)
                        .single();
        jdbc.sql(
                        """
                INSERT INTO user_sessions
                    (id, sid, user_id, status, remembered, admin_session, credentials_version, security_epoch,
                     created_at, last_seen_at, idle_expires_at, absolute_expires_at, last_password_auth_at)
                VALUES (:id, :sid, :userId, 'ACTIVE', false, false, 1, :epoch,
                        :now, :now, :idle, :absolute, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("sid", "s4-session-" + suffix)
                .param("userId", account.userId())
                .param("epoch", epoch)
                .param("now", now)
                .param("idle", now.plusHours(1))
                .param("absolute", now.plusDays(1))
                .update();
        var client =
                RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId("s4-client-" + suffix)
                        .clientSecret("{noop}s4-client-fixture-secret-" + suffix)
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://client.example/callback")
                        .scope("openid")
                        .build();
        clients.save(client);
        jdbc.sql(
                        """
                INSERT INTO registered_client_backchannel_logout_uris (registered_client_id, uri, session_required)
                VALUES (:id, 'http://127.0.0.1:65534/s4-fixture', true)
                """)
                .param("id", client.getId())
                .update();
        authorizations.save(
                OAuth2Authorization.withRegisteredClient(client)
                        .id(UUID.randomUUID().toString())
                        .principalName(account.userId().toString())
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizedScopes(java.util.Set.of("openid"))
                        .build());
        return new Fixture(account.userId(), requestId, actor.userId(), password);
    }

    private List<List<String>> snapshot(UUID userId) {
        var rows = new ArrayList<List<String>>();
        for (String table :
                List.of(
                        "users",
                        "password_credentials",
                        "recovery_codes",
                        "recovery_sessions",
                        "user_sessions",
                        "account_deletion_requests",
                        "admin_recovery_credentials",
                        "security_events",
                        "outbox_events",
                        "admin_audit_events",
                        "oauth2_authorization")) {
            String column =
                    switch (table) {
                        case "users" -> "id";
                        case "admin_recovery_credentials" -> "target_user_id";
                        case "admin_audit_events" -> "target_id";
                        case "outbox_events" -> "aggregate_id";
                        case "oauth2_authorization" -> "principal_name";
                        default -> "user_id";
                    };
            // Canonical full row snapshots, including hashes/times/versions/status; no secrets are
            // printed on success.
            rows.add(
                    jdbc.sql(
                                    "SELECT to_jsonb(t)::text FROM "
                                            + table
                                            + " t WHERE "
                                            + column
                                            + "::text = :id ORDER BY to_jsonb(t)::text")
                            .param("id", userId.toString())
                            .query(String.class)
                            .list());
        }
        return rows;
    }

    private String hash(UUID userId) {
        return jdbc.sql("SELECT password_hash FROM password_credentials WHERE user_id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    private void assertSuccessfulPromotion(Fixture fixture, long expectedVersion) {
        assertThat(
                        jdbc.sql("SELECT is_sso_admin FROM users WHERE id = :id")
                                .param("id", fixture.userId())
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(
                        jdbc.sql("SELECT state::text FROM password_credentials WHERE user_id = :id")
                                .param("id", fixture.userId())
                                .query(String.class)
                                .single())
                .isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(encoder.matches("s4 temporary admin password phrase", hash(fixture.userId())))
                .isTrue();
        assertThat(version(fixture.userId())).isEqualTo(expectedVersion);
        assertThat(
                        jdbc.sql(
                                        "SELECT credentials_version FROM password_credentials WHERE user_id = :id")
                                .param("id", fixture.userId())
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM recovery_sessions WHERE user_id = :id AND consumed_at IS NULL")
                                .param("id", fixture.userId())
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM recovery_codes WHERE user_id = :id AND revoked_at IS NULL")
                                .param("id", fixture.userId())
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM admin_recovery_credentials WHERE target_user_id = :id AND revoked_at IS NULL")
                                .param("id", fixture.userId())
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM user_sessions WHERE user_id = :id AND status = 'ACTIVE'")
                                .param("id", fixture.userId())
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = :id")
                                .param("id", fixture.userId().toString())
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM admin_audit_events WHERE target_id = :id AND action = 'ADMIN_BOOTSTRAP_PROMOTE'")
                                .param("id", fixture.userId().toString())
                                .query(Long.class)
                                .single())
                .isOne();
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = :id AND event_type = 'BACKCHANNEL_LOGOUT_REQUESTED'")
                                .param("id", fixture.userId().toString())
                                .query(Long.class)
                                .single())
                .isOne();
    }

    private String requestSnapshot(UUID requestId) {
        return jdbc.sql("SELECT to_jsonb(t)::text FROM account_deletion_requests t WHERE id = :id")
                .param("id", requestId)
                .query(String.class)
                .single();
    }

    private long version(UUID userId) {
        return jdbc.sql("SELECT service_access_state_version FROM users WHERE id = :id")
                .param("id", userId)
                .query(Long.class)
                .single();
    }

    private String promotionOutcome(UUID userId) {
        try {
            bootstrap.promoteExistingUser(userId, "s4 temporary admin password phrase");
            return "SUCCESS";
        } catch (AdminBootstrapException exception) {
            return exception.code();
        }
    }

    private String inTransaction(
            AtomicInteger connection, CountDownLatch entered, Supplier<?> action) {
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            tx -> {
                                connection.set(pid());
                                entered.countDown();
                                action.get();
                            });
            return "SUCCESS";
        } catch (AdminBootstrapException exception) {
            return exception.code();
        } catch (AccountDeletionConflictException exception) {
            return exception.code();
        }
    }

    private int lockUser(UUID userId) {
        jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE")
                .param("id", userId)
                .query(UUID.class)
                .single();
        return pid();
    }

    private int pid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private void awaitBlocking(int owner, AtomicInteger follower, CountDownLatch entered) {
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < end) {
                if (jdbc.sql("SELECT :owner = ANY(pg_blocking_pids(:follower))")
                        .param("owner", owner)
                        .param("follower", follower.get())
                        .query(Boolean.class)
                        .single()) return;
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
            throw new AssertionError(
                    "Promotion/account operation did not wait on the owning user transaction");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture interrupted", exception);
        }
    }

    private void due(Fixture fixture) {
        jdbc.sql(
                        """
                UPDATE account_deletion_requests SET requested_at = CURRENT_TIMESTAMP - INTERVAL '31 days',
                    execute_after = CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE id = :id
                """)
                .param("id", fixture.requestId())
                .update();
    }

    private enum EffectiveDeletion {
        PENDING,
        HELD
    }

    private enum NonActive {
        FROZEN,
        DELETED
    }

    private record Fixture(UUID userId, UUID requestId, UUID actorId, String password) {}
}
