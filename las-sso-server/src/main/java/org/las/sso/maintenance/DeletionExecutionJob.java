package org.las.sso.maintenance;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.las.sso.account.DeletionRequestService;
import org.las.sso.identity.UuidV7Generator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 注销申请执行任务：每 5 分钟抢占一次执行权，先释放已到期的暂缓申请，
 * 再执行到期的 PENDING 申请。使用 PostgreSQL advisory lock 保证多实例只有一个执行者。
 */
@Service
public class DeletionExecutionJob {

    private static final long ADVISORY_LOCK_ID = 8_815_002_911_303L;
    private static final int BATCH_SIZE = 100;

    private final JdbcClient jdbcClient;
    private final DeletionRequestService deletions;
    private final UuidV7Generator uuidGenerator;
    private final Clock clock;
    private final TransactionTemplate transactions;

    @Autowired
    public DeletionExecutionJob(
            JdbcClient jdbcClient,
            DeletionRequestService deletions,
            UuidV7Generator uuidGenerator,
            PlatformTransactionManager transactionManager) {
        this(jdbcClient, deletions, uuidGenerator, Clock.systemUTC(),
                new TransactionTemplate(transactionManager));
    }

    DeletionExecutionJob(
            JdbcClient jdbcClient,
            DeletionRequestService deletions,
            UuidV7Generator uuidGenerator,
            Clock clock,
            TransactionTemplate transactions) {
        this.jdbcClient = jdbcClient;
        this.deletions = deletions;
        this.uuidGenerator = uuidGenerator;
        this.clock = clock;
        this.transactions = transactions;
    }

    @Scheduled(
            initialDelayString = "${las.sso.deletion-job.initial-delay:PT1M}",
            fixedDelayString = "${las.sso.deletion-job.interval:PT5M}")
    public void scheduledRun() {
        transactions.executeWithoutResult(status -> runWithinTransaction());
    }

    public int run() {
        Integer result = transactions.execute(status -> runWithinTransaction());
        return result == null ? 0 : result;
    }

    private int runWithinTransaction() {
        boolean lockAcquired = jdbcClient.sql("SELECT pg_try_advisory_xact_lock(:lockId)")
                .param("lockId", ADVISORY_LOCK_ID)
                .query(Boolean.class)
                .single();
        if (!lockAcquired) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int released = releaseExpiredHoldsWithinTransaction(now);
        int executed = deletions.executeDue(BATCH_SIZE);
        return released + executed;
    }

    public int releaseExpiredHolds(OffsetDateTime now) {
        Integer result = transactions.execute(status -> releaseExpiredHoldsWithinTransaction(now));
        return result == null ? 0 : result;
    }

    private int releaseExpiredHoldsWithinTransaction(OffsetDateTime now) {
        List<UUID> expired = jdbcClient.sql("""
                WITH selected AS (
                    SELECT id
                      FROM account_deletion_requests
                     WHERE status = 'HELD'
                       AND hold_until IS NOT NULL
                       AND hold_until < :now
                     ORDER BY hold_until, id
                     LIMIT :limit
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE account_deletion_requests request
                   SET status = 'PENDING', hold_until = NULL,
                       held_by_user_id = NULL, hold_reason = NULL,
                       hold_public_summary = NULL
                  FROM selected
                 WHERE request.id = selected.id
                RETURNING request.user_id
                """).param("now", now).param("limit", BATCH_SIZE)
                .query(UUID.class).list();
        if (expired.isEmpty()) {
            return 0;
        }
        for (UUID userId : expired) {
            jdbcClient.sql("""
                    INSERT INTO security_events
                        (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                    VALUES (:id, :now, :userId, 'DELETION_HOLD_EXPIRED',
                            'INFO', :requestId, '{}'::jsonb)
                    """).param("id", uuidGenerator.generate()).param("now", now)
                    .param("userId", userId).param("requestId", uuidGenerator.generate()).update();
        }
        return expired.size();
    }
}
