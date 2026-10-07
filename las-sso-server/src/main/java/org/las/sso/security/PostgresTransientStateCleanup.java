package org.las.sso.security;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostgresTransientStateCleanup {

    private static final long ADVISORY_LOCK_ID = 4_773_916_220_241L;
    private static final int DEFAULT_BATCH_SIZE = 1_000;

    private final JdbcClient jdbcClient;
    private final Clock clock;

    @Autowired
    public PostgresTransientStateCleanup(JdbcClient jdbcClient) {
        this(jdbcClient, Clock.systemUTC());
    }

    PostgresTransientStateCleanup(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${las.sso.transient-security.cleanup-initial-delay:PT5M}",
            fixedDelayString = "${las.sso.transient-security.cleanup-interval:PT5M}")
    @Transactional
    public void scheduledCleanup() {
        cleanupExpired(DEFAULT_BATCH_SIZE);
    }

    @Transactional
    public int cleanupExpired(int batchSize) {
        if (batchSize <= 0 || batchSize > 10_000) {
            throw new IllegalArgumentException("Cleanup batch size must be between 1 and 10000");
        }
        boolean lockAcquired =
                jdbcClient
                        .sql("SELECT pg_try_advisory_xact_lock(:lockId)")
                        .param("lockId", ADVISORY_LOCK_ID)
                        .query(Boolean.class)
                        .single();
        if (!lockAcquired) {
            return 0;
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return deleteExpired("rate_limit_buckets", "window_expires_at", now, batchSize)
                + deleteExpired("captcha_challenges", "expires_at", now, batchSize)
                + deleteExpired("recovery_sessions", "expires_at", now, batchSize)
                + deleteExpired("registration_idempotency", "expires_at", now, batchSize)
                + deleteExpired("operation_idempotency", "expires_at", now, batchSize)
                + deleteExpired("oidc_password_challenges", "expires_at", now, batchSize);
    }

    private int deleteExpired(
            String table, String expiryColumn, OffsetDateTime now, int batchSize) {
        String sql =
                """
                WITH expired AS (
                    SELECT ctid
                      FROM %s
                     WHERE %s < :now
                     ORDER BY %s
                     LIMIT :batchSize
                )
                DELETE FROM %s target
                 USING expired
                 WHERE target.ctid = expired.ctid
                """
                        .formatted(table, expiryColumn, expiryColumn, table);
        return jdbcClient.sql(sql).param("now", now).param("batchSize", batchSize).update();
    }
}
