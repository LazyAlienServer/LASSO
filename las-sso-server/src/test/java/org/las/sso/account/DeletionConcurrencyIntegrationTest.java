package org.las.sso.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.las.sso.admin.AdminDeletionRequestService;
import org.las.sso.admin.AdminUserManagementException;
import org.las.sso.admin.AdminDeletionClockFixture;
import org.las.sso.maintenance.DeletionExecutionJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mockito.Mockito;
import org.las.sso.access.ServiceAccessEligibilityService;
import org.las.sso.identity.UuidV7Generator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class DeletionConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService registration;
    @Autowired DeletionRequestService deletions;
    @Autowired AdminDeletionRequestService adminDeletions;
    @Autowired DeletionExecutionJob job;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ServiceAccessEligibilityService eligibility;
    @Autowired UuidV7Generator ids;

    private final java.util.List<UUID> fixtures = new java.util.ArrayList<>();

    @AfterEach
    void removeRequests() {
        Mockito.reset(eligibility);
        for (UUID id : fixtures) jdbc.sql("DELETE FROM account_deletion_requests WHERE id = :id").param("id", id).update();
    }

    @ParameterizedTest
    @EnumSource(AdminMutation.class)
    void adminMutationWaitingForUserDoesNotAlreadyOwnTheDeletionRequest(AdminMutation mutation) throws Exception {
        var fixture = fixture();
        if (mutation == AdminMutation.RELEASE) hold(fixture);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var followerPid = new AtomicInteger();
            var entered = new CountDownLatch(1);
            var follower = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                int owner = lockUser(fixture.userId());
                follower.set(executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(other -> {
                    followerPid.set(pid());
                    entered.countDown();
                    apply(mutation, fixture);
                })));
                awaitBlocking(owner, followerPid, entered);
                // Another connection must still be able to lock the request while follower waits for user.
                var probe = executor.submit(() -> new TransactionTemplate(transactionManager)
                        .execute(probeStatus -> jdbc.sql("""
                                SELECT id FROM account_deletion_requests WHERE id = :id FOR UPDATE SKIP LOCKED
                                """).param("id", fixture.requestId()).query(UUID.class).optional().isPresent()));
                try {
                    assertThat(probe.get(5, TimeUnit.SECONDS)).isTrue();
                } catch (Exception exception) {
                    throw new AssertionError("Request lock probe failed", exception);
                }
            });
            follower.get().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiredHoldBatchSkipsBusyUserWithoutChangingItsRequest() throws Exception {
        var fixture = fixture();
        hold(fixture);
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                lockUser(fixture.userId());
                var follower = executor.submit(() -> job.releaseExpiredHolds(future));
                try {
                    assertThat(follower.get(2, TimeUnit.SECONDS)).isZero();
                } catch (Exception exception) {
                    throw new AssertionError("Busy user must be skipped before any request lock/update", exception);
                }
                assertThat(state(fixture)).isEqualTo("HELD");
            });
        }
        assertThat(job.releaseExpiredHolds(future)).isOne();
        assertThat(state(fixture)).isEqualTo("PENDING");
        assertThat(job.releaseExpiredHolds(future)).isZero();
        assertThat(events(fixture.userId(), "DELETION_HOLD_EXPIRED")).isOne();
    }

    @ParameterizedTest
    @EnumSource(AdminMutation.class)
    void cancellationCommitWinsOverWaitingAdminMutationWithoutDeadlockOrFalseAudit(AdminMutation mutation)
            throws Exception {
        var fixture = fixture();
        if (mutation == AdminMutation.RELEASE) hold(fixture);
        long version = version(fixture.userId());
        long audit = audits(fixture.userId());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                int owner = lockUser(fixture.userId());
                var followerPid = new AtomicInteger();
                var entered = new CountDownLatch(1);
                follower.set(executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(other -> {
                            followerPid.set(pid());
                            entered.countDown();
                            apply(mutation, fixture);
                        });
                        return "MUTATED";
                    } catch (AdminUserManagementException exception) {
                        return exception.code();
                    }
                }));
                awaitBlocking(owner, followerPid, entered);
                deletions.cancel(fixture.userId(), UUID.randomUUID());
            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS)).isEqualTo("ACCOUNT_STATE_CONFLICT");
        }
        assertThat(state(fixture)).isEqualTo("CANCELLED");
        assertThat(version(fixture.userId())).isEqualTo(version + 1);
        assertThat(audits(fixture.userId())).isEqualTo(audit);
        assertThat(events(fixture.userId(), "DELETION_CANCELLED")).isOne();
    }

    @ParameterizedTest
    @EnumSource(AdminMutation.class)
    void adminMutationCommitIsFollowedByWaitingCancellationAndBothEffectsAreConsistent(AdminMutation mutation)
            throws Exception {
        var fixture = fixture();
        if (mutation == AdminMutation.RELEASE) hold(fixture);
        long version = version(fixture.userId());
        long audit = audits(fixture.userId());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<?>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                apply(mutation, fixture); // Real service acquires and holds user then request in this transaction.
                int owner = pid();
                var followerPid = new AtomicInteger();
                var entered = new CountDownLatch(1);
                follower.set(executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(other -> {
                    followerPid.set(pid());
                    entered.countDown();
                    deletions.cancel(fixture.userId(), UUID.randomUUID());
                })));
                awaitBlocking(owner, followerPid, entered);
            });
            follower.get().get(5, TimeUnit.SECONDS);
        }
        assertThat(state(fixture)).isEqualTo("CANCELLED");
        assertThat(version(fixture.userId())).isEqualTo(version + 2);
        assertThat(audits(fixture.userId())).isEqualTo(audit + 1);
        assertThat(jdbc.sql("""
                SELECT hold_until IS NULL AND held_by_user_id IS NULL AND hold_reason IS NULL
                       AND hold_public_summary IS NULL FROM account_deletion_requests WHERE id = :id
                """).param("id", fixture.requestId()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void deletionCommitMakesWaitingCancellationFailAndCredentialsStayRemoved() throws Exception {
        var fixture = fixture();
        due(fixture);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                deletions.execute(fixture.requestId(), fixture.userId());
                int owner = pid();
                var followerPid = new AtomicInteger();
                var entered = new CountDownLatch(1);
                follower.set(executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(other -> {
                            followerPid.set(pid());
                            entered.countDown();
                            deletions.cancel(fixture.userId(), UUID.randomUUID());
                        });
                        return "CANCELLED";
                    } catch (AccountDeletionConflictException exception) {
                        return exception.code();
                    }
                }));
                awaitBlocking(owner, followerPid, entered);
            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS)).isEqualTo("ACCOUNT_STATE_CONFLICT");
        }
        assertThat(state(fixture)).isEqualTo("EXECUTED");
        assertThat(credentials(fixture.userId())).isZero();
        assertThat(events(fixture.userId(), "ACCOUNT_DELETED")).isOne();
        assertThat(events(fixture.userId(), "DELETION_CANCELLED")).isZero();
    }

    @Test
    void cancellationCommitMakesWaitingDirectExecutionNoOp() throws Exception {
        var fixture = fixture();
        due(fixture);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<?>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                deletions.cancel(fixture.userId(), UUID.randomUUID());
                int owner = pid();
                var followerPid = new AtomicInteger();
                var entered = new CountDownLatch(1);
                follower.set(executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(other -> {
                    followerPid.set(pid());
                    entered.countDown();
                    deletions.execute(fixture.requestId(), fixture.userId());
                })));
                awaitBlocking(owner, followerPid, entered);
            });
            follower.get().get(5, TimeUnit.SECONDS);
        }
        assertThat(state(fixture)).isEqualTo("CANCELLED");
        assertThat(credentials(fixture.userId())).isOne();
        assertThat(events(fixture.userId(), "ACCOUNT_DELETED")).isZero();
    }

    @Test
    void lockedUserAndLockedRequestAreSkippedThenExecutedOnceOnLaterRun() throws Exception {
        var busyUser = fixture();
        var busyRequest = fixture();
        var available = fixture();
        due(busyUser); due(busyRequest); due(available);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                lockUser(busyUser.userId());
                jdbc.sql("SELECT id FROM account_deletion_requests WHERE id = :id FOR UPDATE")
                        .param("id", busyRequest.requestId()).query(UUID.class).single();
                try {
                    assertThat(executor.submit(() -> deletions.executeDue(100)).get(5, TimeUnit.SECONDS)).isOne();
                } catch (Exception exception) {
                    throw new AssertionError("Busy user/request must be skipped, not block the batch", exception);
                }
                assertThat(state(busyUser)).isEqualTo("PENDING");
                assertThat(state(busyRequest)).isEqualTo("PENDING");
                assertThat(state(available)).isEqualTo("EXECUTED");
            });
        }
        assertThat(deletions.executeDue(100)).isEqualTo(2);
        assertThat(deletions.executeDue(100)).isZero();
        for (Fixture target : List.of(busyUser, busyRequest, available)) {
            assertThat(events(target.userId(), "ACCOUNT_DELETED")).isOne();
            assertThat(credentials(target.userId())).isZero();
        }
    }

    @Test
    void scheduledRunLocksCandidateUsersBeforeReleasingExpiredAndExecutingPending() throws Exception {
        var heldBusy = fixture();
        var pendingBusy = fixture();
        var heldReady = fixture();
        var pendingReady = fixture();
        for (Fixture target : List.of(heldBusy, pendingBusy, heldReady, pendingReady)) due(target);
        hold(heldBusy); hold(heldReady);
        expired(heldBusy); expired(heldReady);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                lockUser(heldBusy.userId());
                lockUser(pendingBusy.userId());
                try {
                    assertThat(executor.submit(job::run).get(5, TimeUnit.SECONDS)).isEqualTo(3);
                } catch (Exception exception) {
                    throw new AssertionError("Both job phases must skip busy users before requests", exception);
                }
                assertThat(state(heldBusy)).isEqualTo("HELD");
                assertThat(state(pendingBusy)).isEqualTo("PENDING");
                assertThat(state(heldReady)).isEqualTo("EXECUTED");
                assertThat(state(pendingReady)).isEqualTo("EXECUTED");
            });
        }
        assertThat(job.run()).isEqualTo(3);
        assertThat(job.run()).isZero();
        assertThat(events(heldBusy.userId(), "DELETION_HOLD_EXPIRED")).isOne();
        assertThat(events(heldReady.userId(), "DELETION_HOLD_EXPIRED")).isOne();
    }

    @Test
    void holdCommittedWhileBatchSkipsUserPreventsDeletionUntilReleased() throws Exception {
        var fixture = fixture();
        due(fixture);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                hold(fixture);
                try {
                    assertThat(executor.submit(() -> deletions.executeDue(100)).get(5, TimeUnit.SECONDS)).isZero();
                } catch (Exception exception) {
                    throw new AssertionError("Uncommitted hold's user lock must be skipped", exception);
                }
            });
        }
        assertThat(deletions.executeDue(100)).isZero();
        assertThat(state(fixture)).isEqualTo("HELD");
        apply(AdminMutation.RELEASE, fixture);
        assertThat(deletions.executeDue(100)).isOne();
        assertThat(state(fixture)).isEqualTo("EXECUTED");
    }

    @Test
    void directExecutionRechecksRequestOwnerStatusAndDeadline() {
        var first = fixture();
        var second = fixture();
        long firstVersion = version(first.userId());
        long secondVersion = version(second.userId());
        deletions.execute(first.requestId(), first.userId()); // Not due.
        due(first);
        deletions.execute(first.requestId(), second.userId()); // Deliberately wrong request/user pairing.
        assertThat(state(first)).isEqualTo("PENDING");
        assertThat(state(second)).isEqualTo("PENDING");
        assertThat(version(first.userId())).isEqualTo(firstVersion);
        assertThat(version(second.userId())).isEqualTo(secondVersion);
        assertThat(credentials(first.userId())).isOne();
        assertThat(credentials(second.userId())).isOne();
        hold(first);
        deletions.execute(first.requestId(), first.userId());
        assertThat(state(first)).isEqualTo("HELD");
    }

    @Test
    void failedSecondDeletionRollsBackEntireBatchCredentialsVersionsEventsAndOutbox() {
        var first = fixture();
        var second = fixture();
        due(first); due(second);
        // Fix request due order so the first target truly mutates before the injected second failure.
        jdbc.sql("UPDATE account_deletion_requests SET execute_after = execute_after - INTERVAL '1 minute' WHERE id = :id")
                .param("id", first.requestId()).update();
        long firstVersion = version(first.userId());
        long secondVersion = version(second.userId());
        long firstOutbox = outbox(first.userId());
        long secondOutbox = outbox(second.userId());
        jdbc.sql("""
                CREATE FUNCTION s3_fixture_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.user_id = '%s'::uuid AND NEW.event_type = 'ACCOUNT_DELETED' THEN
                        RAISE EXCEPTION 's3_fixture_batch_failure';
                    END IF;
                    RETURN NEW;
                END; $$
                """.formatted(second.userId())).update();
        try {
            jdbc.sql("CREATE TRIGGER s3_fixture_failure BEFORE INSERT ON security_events "
                    + "FOR EACH ROW EXECUTE FUNCTION s3_fixture_failure()").update();
            try {
                assertThatThrownBy(() -> deletions.executeDue(100)).isInstanceOf(DataAccessException.class);
            } finally {
                jdbc.sql("DROP TRIGGER s3_fixture_failure ON security_events").update();
            }
        } finally {
            jdbc.sql("DROP FUNCTION s3_fixture_failure()").update();
        }
        for (Fixture target : List.of(first, second)) {
            assertThat(state(target)).isEqualTo("PENDING");
            assertThat(credentials(target.userId())).isOne();
            assertThat(events(target.userId(), "ACCOUNT_DELETED")).isZero();
        }
        assertThat(version(first.userId())).isEqualTo(firstVersion);
        assertThat(version(second.userId())).isEqualTo(secondVersion);
        assertThat(outbox(first.userId())).isEqualTo(firstOutbox);
        assertThat(outbox(second.userId())).isEqualTo(secondOutbox);
        assertThat(deletions.executeDue(100)).isEqualTo(2);
    }

    @Test
    void holdReleaseAndCancelFailureBeforeCommitLeaveStatusVersionAndAuditUnchanged() {
        var fixture = fixture();
        long initialVersion = version(fixture.userId());
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            hold(fixture);
            throw new IllegalStateException("Fixture failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state(fixture)).isEqualTo("PENDING");
        assertThat(version(fixture.userId())).isEqualTo(initialVersion);
        assertThat(audits(fixture.userId())).isZero();
        hold(fixture);
        long heldVersion = version(fixture.userId());
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            apply(AdminMutation.RELEASE, fixture);
            throw new IllegalStateException("Fixture failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            deletions.cancel(fixture.userId(), UUID.randomUUID());
            throw new IllegalStateException("Fixture failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state(fixture)).isEqualTo("HELD");
        assertThat(version(fixture.userId())).isEqualTo(heldVersion);
        assertThat(audits(fixture.userId())).isOne();
        assertThat(events(fixture.userId(), "DELETION_RELEASED_BY_ADMIN")).isZero();
        assertThat(events(fixture.userId(), "DELETION_CANCELLED")).isZero();
    }

    @Test
    void batchLimitCountsActualChangesAndRemainingCandidatesAreProcessedLater() {
        var first = fixture();
        var second = fixture();
        due(first); due(second);
        assertThat(deletions.executeDue(1)).isOne();
        assertThat(List.of(state(first), state(second))).containsExactlyInAnyOrder("PENDING", "EXECUTED");
        assertThat(deletions.executeDue(1)).isOne();
        assertThat(deletions.executeDue(1)).isZero();
        assertThatThrownBy(() -> deletions.executeDue(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstJobMutationAlreadyOwnsBothPhasesUsersBeforeAnyNewRequestLock() throws Exception {
        var held = fixture();
        var pending = fixture();
        due(held); due(pending);
        hold(held); expired(held);
        var observed = new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Mockito.doAnswer(invocation -> {
                if (observed.compareAndSet(false, true)) {
                    // On the first eligibility write, a separate DB transaction must find neither user lock free.
                    var freeUsers = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status ->
                            jdbc.sql("SELECT id FROM users WHERE id IN (:ids) ORDER BY id FOR UPDATE SKIP LOCKED")
                                    .param("ids", List.of(held.userId(), pending.userId())).query(UUID.class).list()));
                    assertThat(freeUsers.get(5, TimeUnit.SECONDS)).isEmpty();
                }
                return invocation.callRealMethod();
            }).when(eligibility).advanceStateVersion(Mockito.any(UUID.class), Mockito.any(OffsetDateTime.class));

            assertThat(job.run()).isEqualTo(3);
            assertThat(observed).isTrue();
            assertThat(state(held)).isEqualTo("EXECUTED");
            assertThat(state(pending)).isEqualTo("EXECUTED");
        }
    }

    @Test
    void bothJobPhasesRollBackTogetherWhenDeletionAfterReleaseFails() {
        var held = fixture();
        var pending = fixture();
        due(held); due(pending);
        hold(held); expired(held);
        long heldVersion = version(held.userId());
        long pendingVersion = version(pending.userId());
        long heldOutbox = outbox(held.userId());
        long pendingOutbox = outbox(pending.userId());
        Mockito.doAnswer(invocation -> {
            // Called after the held request became PENDING and deletion reached its final version write.
            if ("DELETED".equals(jdbc.sql("SELECT account_status::text FROM users WHERE id = :id")
                    .param("id", invocation.getArgument(0)).query(String.class).single())) {
                assertThat(state(held)).isIn("PENDING", "EXECUTED");
                throw new IllegalStateException("Fixture failure in second job phase");
            }
            return invocation.callRealMethod();
        }).when(eligibility).advanceStateVersion(Mockito.any(UUID.class), Mockito.any(OffsetDateTime.class));

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);

        assertThat(state(held)).isEqualTo("HELD");
        assertThat(state(pending)).isEqualTo("PENDING");
        assertThat(version(held.userId())).isEqualTo(heldVersion);
        assertThat(version(pending.userId())).isEqualTo(pendingVersion);
        assertThat(outbox(held.userId())).isEqualTo(heldOutbox);
        assertThat(outbox(pending.userId())).isEqualTo(pendingOutbox);
        assertThat(events(held.userId(), "DELETION_HOLD_EXPIRED")).isZero();
        assertThat(credentials(held.userId())).isOne();
        assertThat(credentials(pending.userId())).isOne();
        Mockito.reset(eligibility);
        assertThat(job.run()).isEqualTo(3);
    }

    @Test
    void advisoryOwnedScheduledRunSkipsThenLaterProcessesTheBatch() throws Exception {
        var fixture = fixture();
        due(fixture);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.sql("SELECT true FROM pg_advisory_xact_lock(:key)").param("key", 8_815_002_911_303L)
                        .query(Boolean.class).single();
                try {
                    assertThat(executor.submit(job::run).get(5, TimeUnit.SECONDS)).isZero();
                } catch (Exception exception) {
                    throw new AssertionError("Another scheduled owner must not duplicate the batch", exception);
                }
                assertThat(state(fixture)).isEqualTo("PENDING");
            });
        }
        assertThat(job.run()).isOne();
        assertThat(job.run()).isZero();
        assertThat(events(fixture.userId(), "ACCOUNT_DELETED")).isOne();
    }

    @Test
    void concurrentBatchesDoNotDuplicateSelectedDeletionOrItsOutbox() throws Exception {
        var first = fixture();
        var second = fixture();
        due(first); due(second);
        long firstOutbox = outbox(first.userId());
        long secondOutbox = outbox(second.userId());
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return deletions.executeDue(100); });
            var b = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return deletions.executeDue(100); });
            assertThat(a.get(5, TimeUnit.SECONDS) + b.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        }
        for (Fixture target : List.of(first, second)) {
            assertThat(state(target)).isEqualTo("EXECUTED");
            assertThat(events(target.userId(), "ACCOUNT_DELETED")).isOne();
        }
        assertThat(outbox(first.userId())).isEqualTo(firstOutbox + 1);
        assertThat(outbox(second.userId())).isEqualTo(secondOutbox + 1);
    }

    @Test
    void oldAdminRequestDoesNotAffectNewApplicationAfterCancellationCommit() throws Exception {
        var fixture = fixture();
        UUID newRequest;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            var nextId = new AtomicReference<UUID>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                int owner = lockUser(fixture.userId());
                var followerPid = new AtomicInteger();
                var entered = new CountDownLatch(1);
                follower.set(executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(other -> {
                            followerPid.set(pid());
                            entered.countDown();
                            hold(fixture);
                        });
                        return "HELD";
                    } catch (AdminUserManagementException exception) { return exception.code(); }
                }));
                awaitBlocking(owner, followerPid, entered);
                deletions.cancel(fixture.userId(), UUID.randomUUID());
                deletions.request(fixture.userId(), UUID.randomUUID());
                nextId.set(jdbc.sql("SELECT id FROM account_deletion_requests WHERE user_id = :id AND status = 'PENDING'")
                        .param("id", fixture.userId()).query(UUID.class).single());
            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS)).isEqualTo("ACCOUNT_STATE_CONFLICT");
            newRequest = nextId.get();
        }
        fixtures.add(newRequest);
        assertThat(state(fixture)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT status::text FROM account_deletion_requests WHERE id = :id")
                .param("id", newRequest).query(String.class).single()).isEqualTo("PENDING");
        assertThat(audits(fixture.userId())).isZero();
    }

    @Test
    void changedCandidateOwnershipIsRejectedAfterWaitingForOriginalUser() throws Exception {
        var original = fixture();
        var other = fixture();
        deletions.cancel(other.userId(), UUID.randomUUID());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                int owner = lockUser(original.userId());
                var followerPid = new AtomicInteger();
                var entered = new CountDownLatch(1);
                follower.set(executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(otherStatus -> {
                            followerPid.set(pid());
                            entered.countDown();
                            hold(original);
                        });
                        return "HELD";
                    } catch (AdminUserManagementException exception) { return exception.code(); }
                }));
                awaitBlocking(owner, followerPid, entered);
                // Corrupt candidate only in the test DB to prove ownership is rechecked, not trusted.
                jdbc.sql("UPDATE account_deletion_requests SET user_id = :other WHERE id = :id")
                        .param("other", other.userId()).param("id", original.requestId()).update();
            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS)).isEqualTo("ADMIN_TARGET_NOT_FOUND");
        }
        assertThat(state(original)).isEqualTo("PENDING");
        assertThat(audits(original.userId())).isZero();
        assertThat(audits(other.userId())).isZero();
    }

    @Test
    void expiredHoldRequestLockIsSkippedThenLatestDeadlineAndStatusAreRechecked() throws Exception {
        var fixture = fixture();
        hold(fixture); expired(fixture);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.sql("SELECT id FROM account_deletion_requests WHERE id = :id FOR UPDATE")
                        .param("id", fixture.requestId()).query(UUID.class).single();
                try {
                    assertThat(executor.submit(() -> job.releaseExpiredHolds(OffsetDateTime.now(ZoneOffset.UTC)))
                            .get(5, TimeUnit.SECONDS)).isZero();
                } catch (Exception exception) { throw new AssertionError("Busy hold request must be skipped", exception); }
                jdbc.sql("UPDATE account_deletion_requests SET hold_until = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE id = :id")
                        .param("id", fixture.requestId()).update();
            });
        }
        assertThat(job.releaseExpiredHolds(OffsetDateTime.now(ZoneOffset.UTC))).isZero();
        assertThat(state(fixture)).isEqualTo("HELD");
        deletions.cancel(fixture.userId(), UUID.randomUUID());
        assertThat(job.releaseExpiredHolds(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2))).isZero();
        assertThat(events(fixture.userId(), "DELETION_HOLD_EXPIRED")).isZero();
    }

    @Test
    void holdDeadlineExpiringDuringUserLockWaitIsRejectedWithoutStateOrAuditChanges() throws Exception {
        var fixture = fixture();
        Instant start = Instant.parse("2026-10-04T00:00:00Z");
        var current = new AtomicReference<>(start);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        var timedService = AdminDeletionClockFixture.service(jdbc, ids, eligibility, clock);
        long originalVersion = version(fixture.userId());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var follower = new AtomicReference<Future<String>>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                int owner = lockUser(fixture.userId());
                var followerPid = new AtomicInteger();
                var entered = new CountDownLatch(1);
                follower.set(executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(other -> {
                            followerPid.set(pid());
                            entered.countDown();
                            timedService.hold(fixture.actorId(), null, fixture.requestId(), "fixture wait deadline",
                                    "核验中", start.plusSeconds(1).atOffset(ZoneOffset.UTC), UUID.randomUUID());
                        });
                        return "HELD";
                    } catch (AdminUserManagementException exception) { return exception.code(); }
                }));
                awaitBlocking(owner, followerPid, entered);
                current.set(start.plusSeconds(2)); // Advance fixture clock, no real-time sleeping.
            });
            assertThat(follower.get().get(5, TimeUnit.SECONDS)).isEqualTo("INVALID_HOLD_UNTIL");
        }
        assertThat(state(fixture)).isEqualTo("PENDING");
        assertThat(version(fixture.userId())).isEqualTo(originalVersion);
        assertThat(audits(fixture.userId())).isZero();
        assertThat(events(fixture.userId(), "DELETION_HELD_BY_ADMIN")).isZero();
    }

    private void due(Fixture fixture) {
        jdbc.sql("""
                UPDATE account_deletion_requests
                   SET requested_at = CURRENT_TIMESTAMP - INTERVAL '31 days',
                       execute_after = CURRENT_TIMESTAMP - INTERVAL '1 day'
                 WHERE id = :id
                """).param("id", fixture.requestId()).update();
    }

    private void expired(Fixture fixture) {
        jdbc.sql("UPDATE account_deletion_requests SET hold_until = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id")
                .param("id", fixture.requestId()).update();
    }

    private long version(UUID userId) {
        return jdbc.sql("SELECT service_access_state_version FROM users WHERE id = :id")
                .param("id", userId).query(Long.class).single();
    }

    private long credentials(UUID userId) {
        return jdbc.sql("SELECT COUNT(*) FROM password_credentials WHERE user_id = :id")
                .param("id", userId).query(Long.class).single();
    }

    private long events(UUID userId, String type) {
        return jdbc.sql("SELECT COUNT(*) FROM security_events WHERE user_id = :id AND event_type = :type")
                .param("id", userId).param("type", type).query(Long.class).single();
    }

    private long audits(UUID userId) {
        return jdbc.sql("SELECT COUNT(*) FROM admin_audit_events WHERE target_id = :id")
                .param("id", userId.toString()).query(Long.class).single();
    }

    private long outbox(UUID userId) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = :id")
                .param("id", userId.toString()).query(Long.class).single();
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var owner = registration.register("S3" + suffix, "Deletion concurrency owner", "s3 owner initial password phrase");
        var actor = registration.register("S3Actor" + suffix, "Deletion concurrency actor", "s3 actor initial password phrase");
        deletions.request(owner.userId(), UUID.randomUUID());
        UUID requestId = jdbc.sql("SELECT id FROM account_deletion_requests WHERE user_id = :id AND status = 'PENDING'")
                .param("id", owner.userId()).query(UUID.class).single();
        fixtures.add(requestId);
        return new Fixture(owner.userId(), requestId, actor.userId());
    }

    private void hold(Fixture fixture) {
        adminDeletions.hold(fixture.actorId(), null, fixture.requestId(), "fixture verified dispute", "核验中",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), UUID.randomUUID());
    }

    private void apply(AdminMutation mutation, Fixture fixture) {
        if (mutation == AdminMutation.HOLD) hold(fixture);
        else adminDeletions.release(fixture.actorId(), null, fixture.requestId(), "fixture resolved", UUID.randomUUID());
    }

    private int lockUser(UUID userId) {
        jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE").param("id", userId).query(UUID.class).single();
        return pid();
    }

    private int pid() { return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single(); }

    private void awaitBlocking(int owner, AtomicInteger follower, CountDownLatch entered) {
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < end) {
                if (jdbc.sql("SELECT :owner = ANY(pg_blocking_pids(:follower))").param("owner", owner)
                        .param("follower", follower.get()).query(Boolean.class).single()) return;
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
            throw new AssertionError("Competing operation never waited on owning PostgreSQL user transaction");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture interrupted", exception);
        }
    }

    private String state(Fixture fixture) {
        return jdbc.sql("SELECT status::text FROM account_deletion_requests WHERE id = :id")
                .param("id", fixture.requestId()).query(String.class).single();
    }

    private enum AdminMutation { HOLD, RELEASE }
    private record Fixture(UUID userId, UUID requestId, UUID actorId) { }
}
