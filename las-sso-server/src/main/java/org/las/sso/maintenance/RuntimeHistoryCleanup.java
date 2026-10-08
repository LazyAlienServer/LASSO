package org.las.sso.maintenance;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import org.las.sso.config.HistoryRetentionProperties;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Finite independently committed phases; no network calls, audit deletion or live hint pruning. */
@Service
public class RuntimeHistoryCleanup {
    private static final long LOCK = 4_773_916_220_242L;

    public enum Phase {
        SESSION_EXPIRY,
        AUTHORIZATIONS,
        SESSIONS,
        REFRESH_HISTORY,
        RECOVERY_CODES,
        MANUAL_CREDENTIALS,
        OUTBOX
    }

    private final JdbcClient jdbc;
    private final HistoryRetentionProperties properties;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;

    // Unknown expiry for an existing Token protects the authorization. At least one safe deadline
    // is required.
    private static final String AUTHORIZATION_EXPIRED =
            """
            GREATEST(a.authorization_code_expires_at, a.access_token_expires_at, a.oidc_id_token_expires_at,
                     a.refresh_token_expires_at, a.user_code_expires_at, a.device_code_expires_at) < :cutoff
            AND (s.family_absolute_expires_at IS NULL OR s.family_absolute_expires_at < :cutoff)
            AND (a.authorization_code_value IS NULL OR a.authorization_code_expires_at IS NOT NULL)
            AND (a.access_token_value IS NULL OR a.access_token_expires_at IS NOT NULL)
            AND (a.oidc_id_token_value IS NULL OR a.oidc_id_token_expires_at IS NOT NULL)
            AND (a.refresh_token_value IS NULL OR a.refresh_token_expires_at IS NOT NULL AND s.family_absolute_expires_at IS NOT NULL)
            AND (s.token_family_id IS NULL OR s.family_absolute_expires_at IS NOT NULL)
            AND (a.user_code_value IS NULL OR a.user_code_expires_at IS NOT NULL)
            AND (a.device_code_value IS NULL OR a.device_code_expires_at IS NOT NULL)
            AND NOT EXISTS (SELECT 1 FROM oauth2_refresh_token_history h
                             WHERE h.authorization_id = a.id AND h.expires_at >= :cutoff)
            """;

    public RuntimeHistoryCleanup(
            JdbcClient jdbc,
            HistoryRetentionProperties properties,
            PlatformTransactionManager manager,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.metrics = metrics;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(10);
        for (Phase phase : Phase.values()) {
            metrics.counter(
                    "las.sso.history.cleanup.rows",
                    "phase",
                    phase.name().toLowerCase(java.util.Locale.ROOT));
            metrics.counter(
                    "las.sso.history.cleanup.failures",
                    "phase",
                    phase.name().toLowerCase(java.util.Locale.ROOT));
            metrics.counter(
                    "las.sso.history.cleanup.skipped.runs",
                    "phase",
                    phase.name().toLowerCase(java.util.Locale.ROOT));
            metrics.counter(
                    "las.sso.history.cleanup.completed.runs",
                    "phase",
                    phase.name().toLowerCase(java.util.Locale.ROOT));
        }
        metrics.counter("las.sso.history.cleanup.oversized.authorizations");
        metrics.counter("las.sso.history.cleanup.busy.authorizations");
    }

    @Scheduled(
            initialDelayString = "${las.sso.history.cleanup-initial-delay:PT5M}",
            fixedDelayString = "${las.sso.history.cleanup-interval:PT5M}")
    public void scheduledCleanup() {
        for (Phase phase : Phase.values()) {
            try {
                cleanup(phase);
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(RuntimeHistoryCleanup.class)
                        .error(
                                "Runtime history cleanup incomplete: phase={}, errorCode=STORAGE_OR_TIMEOUT",
                                phase.name());
            }
        }
    }

    public Map<Phase, Integer> cleanup() {
        var result = new EnumMap<Phase, Integer>(Phase.class);
        for (Phase phase : Phase.values()) result.put(phase, cleanup(phase));
        return Map.copyOf(result);
    }

    public int cleanup(Phase phase) {
        String label = phase.name().toLowerCase(java.util.Locale.ROOT);
        long started = System.nanoTime();
        try {
            int rows =
                    transactions.execute(
                            status -> {
                                jdbc.sql("SET LOCAL lock_timeout = '500ms'").update();
                                jdbc.sql("SET LOCAL statement_timeout = '5s'").update();
                                boolean acquired =
                                        jdbc.sql("SELECT pg_try_advisory_xact_lock(:lock)")
                                                .param("lock", LOCK)
                                                .query(Boolean.class)
                                                .single();
                                if (!acquired) return -1;
                                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                                return switch (phase) {
                                    case SESSION_EXPIRY -> expireSessions(now);
                                    case AUTHORIZATIONS ->
                                            reclaimAuthorizations(
                                                    now.minus(properties.authorizationRetention()),
                                                    status);
                                    case SESSIONS ->
                                            purge(
                                                    "user_sessions",
                                                    "id",
                                                    "target.status IN ('REVOKED', 'EXPIRED') AND target.revoked_at < :cutoff AND NOT EXISTS (SELECT 1 FROM oauth2_authorization_security s WHERE s.sid = target.sid)",
                                                    "target.revoked_at, target.id",
                                                    now.minus(properties.sessionRetention()));
                                    case REFRESH_HISTORY ->
                                            purge(
                                                    "oauth2_refresh_token_history",
                                                    "token_hash",
                                                    "target.status = 'REVOKED' AND target.expires_at < :cutoff AND target.revoked_at < :cutoff AND NOT EXISTS (SELECT 1 FROM oauth2_authorization a WHERE a.id = target.authorization_id)",
                                                    "target.expires_at, target.token_hash",
                                                    now.minus(
                                                            properties.refreshHistoryRetention()));
                                    case RECOVERY_CODES ->
                                            purge(
                                                    "recovery_codes",
                                                    "id",
                                                    "(target.used_at IS NOT NULL OR target.revoked_at IS NOT NULL) AND GREATEST(target.used_at, target.revoked_at) < :cutoff",
                                                    "GREATEST(target.used_at, target.revoked_at), target.id",
                                                    now.minus(properties.credentialRetention()));
                                    case MANUAL_CREDENTIALS ->
                                            purge(
                                                    "admin_recovery_credentials",
                                                    "id",
                                                    "(target.used_at IS NOT NULL OR target.revoked_at IS NOT NULL) AND GREATEST(target.used_at, target.revoked_at, target.expires_at) < :cutoff",
                                                    "GREATEST(target.used_at, target.revoked_at, target.expires_at), target.id",
                                                    now.minus(properties.credentialRetention()));
                                    case OUTBOX ->
                                            purge(
                                                    "outbox_events",
                                                    "id",
                                                    "target.processed_at < :cutoff AND target.dead_lettered_at IS NULL AND target.claim_token IS NULL AND target.claimed_at IS NULL AND target.event_type IN ('BACKCHANNEL_LOGOUT_REQUESTED', 'GLOBAL_LOGOUT_REQUESTED')",
                                                    "target.processed_at, target.id",
                                                    now.minus(properties.outboxRetention()));
                                };
                            });
            // TransactionTemplate has committed at this point; rollbacks never publish deleted
            // rows.
            if (rows < 0) {
                metrics.counter("las.sso.history.cleanup.skipped.runs", "phase", label).increment();
                return 0;
            }
            metrics.counter("las.sso.history.cleanup.rows", "phase", label).increment(rows);
            metrics.counter("las.sso.history.cleanup.completed.runs", "phase", label).increment();
            return rows;
        } catch (RuntimeException failure) {
            metrics.counter("las.sso.history.cleanup.failures", "phase", label).increment();
            throw failure;
        } finally {
            metrics.timer("las.sso.history.cleanup.duration", "phase", label)
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private int expireSessions(OffsetDateTime now) {
        return jdbc.sql(
                        """
                WITH candidates AS (
                    SELECT id FROM user_sessions WHERE status = 'ACTIVE'
                     AND LEAST(idle_expires_at, absolute_expires_at) <= :now
                     ORDER BY LEAST(idle_expires_at, absolute_expires_at), id LIMIT :batch FOR UPDATE SKIP LOCKED)
                UPDATE user_sessions target SET status = 'EXPIRED',
                    revoked_at = LEAST(target.idle_expires_at, target.absolute_expires_at), revoke_reason = 'SESSION_EXPIRED'
                  FROM candidates WHERE target.id = candidates.id AND target.status = 'ACTIVE'
                    AND LEAST(target.idle_expires_at, target.absolute_expires_at) <= :now
                """)
                .param("now", now)
                .param("batch", properties.batchSize())
                .update();
    }

    private int reclaimAuthorizations(
            OffsetDateTime cutoff, org.springframework.transaction.TransactionStatus status) {
        var candidates =
                jdbc.sql(
                                "SELECT a.id FROM oauth2_authorization a LEFT JOIN oauth2_authorization_security s ON s.authorization_id = a.id WHERE "
                                        + AUTHORIZATION_EXPIRED
                                        + " ORDER BY GREATEST(a.authorization_code_expires_at, a.access_token_expires_at, a.oidc_id_token_expires_at, a.refresh_token_expires_at, a.user_code_expires_at, a.device_code_expires_at), a.id LIMIT :candidates")
                        .param("cutoff", cutoff)
                        .param("candidates", properties.batchSize() * 10)
                        .query(String.class)
                        .list();
        int deleted = 0;
        for (String id : candidates) {
            if (deleted >= properties.batchSize()) break;
            Object savepoint = status.createSavepoint();
            // Refresh locks its hash before authorization. Do not invert that order in the DELETE
            // trigger.
            long totalHistory =
                    jdbc.sql(
                                    "SELECT count(*) FROM oauth2_refresh_token_history WHERE authorization_id = :id")
                            .param("id", id)
                            .query(Long.class)
                            .single();
            // An oversized family needs smaller/operator handling, not unbounded JVM allocation.
            if (totalHistory > 10000) {
                metrics.counter("las.sso.history.cleanup.oversized.authorizations").increment();
                status.releaseSavepoint(savepoint);
                continue;
            }
            var lockedHistory =
                    jdbc.sql(
                                    "SELECT token_hash FROM oauth2_refresh_token_history WHERE authorization_id = :id ORDER BY token_hash LIMIT 10001 FOR UPDATE SKIP LOCKED")
                            .param("id", id)
                            .query(byte[].class)
                            .list();
            if (lockedHistory.size() != totalHistory) {
                metrics.counter("las.sso.history.cleanup.busy.authorizations").increment();
                status.rollbackToSavepoint(savepoint);
                status.releaseSavepoint(savepoint);
                continue;
            }
            if (jdbc.sql(
                            "SELECT id FROM oauth2_authorization WHERE id = :id FOR UPDATE SKIP LOCKED")
                    .param("id", id)
                    .query(String.class)
                    .optional()
                    .isEmpty()) {
                metrics.counter("las.sso.history.cleanup.busy.authorizations").increment();
                status.rollbackToSavepoint(savepoint);
                status.releaseSavepoint(savepoint);
                continue;
            }
            var security =
                    jdbc.sql(
                                    "SELECT authorization_id FROM oauth2_authorization_security WHERE authorization_id = :id FOR UPDATE SKIP LOCKED")
                            .param("id", id)
                            .query(String.class)
                            .optional();
            if (security.isEmpty()
                    && jdbc.sql(
                                            "SELECT count(*) FROM oauth2_authorization_security WHERE authorization_id = :id")
                                    .param("id", id)
                                    .query(Long.class)
                                    .single()
                            > 0) {
                metrics.counter("las.sso.history.cleanup.busy.authorizations").increment();
                status.rollbackToSavepoint(savepoint);
                status.releaseSavepoint(savepoint);
                continue;
            }
            long currentHistory =
                    jdbc.sql(
                                    "SELECT count(*) FROM oauth2_refresh_token_history WHERE authorization_id = :id")
                            .param("id", id)
                            .query(Long.class)
                            .single();
            if (lockedHistory.size() != currentHistory) {
                status.rollbackToSavepoint(savepoint);
                status.releaseSavepoint(savepoint);
                continue;
            }
            deleted +=
                    jdbc.sql(
                                    "DELETE FROM oauth2_authorization a WHERE a.id = :id AND EXISTS (SELECT 1 FROM oauth2_authorization candidate LEFT JOIN oauth2_authorization_security s ON s.authorization_id = candidate.id WHERE candidate.id = a.id AND "
                                            + AUTHORIZATION_EXPIRED.replace("a.", "candidate.")
                                            + ")")
                            .param("id", id)
                            .param("cutoff", cutoff)
                            .update();
            status.releaseSavepoint(savepoint);
        }
        return deleted;
    }

    private int purge(
            String table, String key, String predicate, String order, OffsetDateTime cutoff) {
        // All identifiers/predicates are closed internal constants, never request or config SQL.
        return jdbc.sql(
                        "WITH candidates AS (SELECT target."
                                + key
                                + " FROM "
                                + table
                                + " target WHERE "
                                + predicate
                                + " ORDER BY "
                                + order
                                + " LIMIT :batch FOR UPDATE OF target SKIP LOCKED) DELETE FROM "
                                + table
                                + " target USING candidates WHERE target."
                                + key
                                + " = candidates."
                                + key
                                + " AND "
                                + predicate)
                .param("cutoff", cutoff)
                .param("batch", properties.batchSize())
                .update();
    }
}
