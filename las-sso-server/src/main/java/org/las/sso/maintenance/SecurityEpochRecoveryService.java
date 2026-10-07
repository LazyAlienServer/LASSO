package org.las.sso.maintenance;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.las.sso.identity.UuidV7Generator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the database side of a restore barrier. Key replacement and consumer cache barriers are
 * deliberately operator-owned steps around this transaction.
 */
@Service
public class SecurityEpochRecoveryService {
    private final JdbcClient jdbc;
    private final UuidV7Generator ids;
    private final Clock clock;

    @Autowired
    public SecurityEpochRecoveryService(JdbcClient jdbc, UuidV7Generator ids) {
        this(jdbc, ids, Clock.systemUTC());
    }

    SecurityEpochRecoveryService(JdbcClient jdbc, UuidV7Generator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public RecoveryResult rotateAfterRestore(String expectedEpoch, String operatorReason) {
        if (expectedEpoch == null || expectedEpoch.isBlank() || expectedEpoch.length() > 64) {
            throw new SecurityEpochRecoveryException("EXPECTED_RESTORED_EPOCH_REQUIRED");
        }
        if (operatorReason == null || operatorReason.isBlank() || operatorReason.length() > 128) {
            throw new IllegalArgumentException("A bounded recovery reason is required");
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String oldEpoch =
                jdbc.sql("SELECT security_epoch FROM system_security_state WHERE id = 1 FOR UPDATE")
                        .query(String.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        new SecurityEpochRecoveryException(
                                                "SECURITY_EPOCH_UNAVAILABLE"));
        if (oldEpoch.isBlank())
            throw new SecurityEpochRecoveryException("SECURITY_EPOCH_UNAVAILABLE");
        if (!expectedEpoch.equals(oldEpoch))
            throw new SecurityEpochRecoveryException("RESTORED_EPOCH_MISMATCH");
        String newEpoch = UUID.randomUUID().toString();
        jdbc.sql(
                        """
                UPDATE system_security_state
                   SET security_epoch = :newEpoch, updated_at = :now, change_reason = :reason
                 WHERE id = 1
                """)
                .param("newEpoch", newEpoch)
                .param("now", now)
                .param("reason", operatorReason)
                .update();
        jdbc.sql(
                        """
                UPDATE user_sessions
                   SET status = 'REVOKED', revoked_at = :now, revoke_reason = 'RESTORE_EPOCH_ROTATED'
                 WHERE status = 'ACTIVE'
                """)
                .param("now", now)
                .update();
        jdbc.sql("DELETE FROM spring_session").update();
        jdbc.sql("DELETE FROM oauth2_authorization").update();
        jdbc.sql(
                        """
                UPDATE oauth2_refresh_token_history
                   SET status = 'REVOKED', revoked_at = COALESCE(revoked_at, :now)
                 WHERE status <> 'REVOKED'
                """)
                .param("now", now)
                .update();
        jdbc.sql("DELETE FROM oidc_password_challenges").update();
        jdbc.sql("DELETE FROM rate_limit_buckets").update();
        jdbc.sql("DELETE FROM captcha_challenges").update();
        jdbc.sql("DELETE FROM recovery_sessions").update();
        jdbc.sql("DELETE FROM registration_idempotency").update();
        jdbc.sql("DELETE FROM operation_idempotency").update();
        jdbc.sql(
                        """
                UPDATE outbox_events
                   SET claim_token = NULL, claimed_at = NULL
                 WHERE processed_at IS NULL AND dead_lettered_at IS NULL
                """)
                .update();
        jdbc.sql(
                        """
                INSERT INTO security_events
                    (id, occurred_at, event_type, severity, request_id, details_json)
                VALUES (:id, :now, 'SECURITY_EPOCH_ROTATED_AFTER_RESTORE', 'CRITICAL', :requestId,
                        jsonb_build_object('oldEpoch', :oldEpoch, 'newEpoch', :newEpoch, 'reason', :reason))
                """)
                .param("id", ids.generate())
                .param("now", now)
                .param("requestId", ids.generate())
                .param("oldEpoch", oldEpoch)
                .param("newEpoch", newEpoch)
                .param("reason", operatorReason)
                .update();
        return new RecoveryResult(oldEpoch, newEpoch, now);
    }

    public record RecoveryResult(String oldEpoch, String newEpoch, OffsetDateTime rotatedAt) {}
}
