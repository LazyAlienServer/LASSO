package org.las.sso.maintenance;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.las.sso.access.ServiceAccessEligibilityService;
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
    private final Clock clock;
    private final TransactionTemplate transactions;

    @Autowired
    public DeletionExecutionJob(
            JdbcClient jdbcClient,
            DeletionRequestService deletions,
            ServiceAccessEligibilityService serviceAccess,
            UuidV7Generator uuidGenerator,
            PlatformTransactionManager transactionManager) {
        this(jdbcClient, deletions, serviceAccess, uuidGenerator, Clock.systemUTC(),
                new TransactionTemplate(transactionManager));
    }

    DeletionExecutionJob(
            JdbcClient jdbcClient,
            DeletionRequestService deletions,
            ServiceAccessEligibilityService serviceAccess,
            UuidV7Generator uuidGenerator,
            Clock clock,
            TransactionTemplate transactions) {
        this.jdbcClient = jdbcClient;
        this.deletions = deletions;
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
        return deletions.processDueBatch(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), BATCH_SIZE);
    }

    public int releaseExpiredHolds(OffsetDateTime now) {
        Integer result = transactions.execute(status -> deletions.releaseExpiredHolds(now, BATCH_SIZE));
        return result == null ? 0 : result;
    }

}
