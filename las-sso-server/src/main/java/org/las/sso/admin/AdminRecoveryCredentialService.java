package org.las.sso.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.las.sso.account.RecoveryCodeService;
import org.las.sso.identity.UuidV7Generator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminRecoveryCredentialService {

    public static final Duration LIFETIME = Duration.ofMinutes(30);

    private final JdbcClient jdbcClient;
    private final RecoveryCodeService recoveryCodes;
    private final UuidV7Generator uuidGenerator;
    private final Clock clock;

    @Autowired
    public AdminRecoveryCredentialService(
            JdbcClient jdbcClient,
            RecoveryCodeService recoveryCodes,
            UuidV7Generator uuidGenerator) {
        this(jdbcClient, recoveryCodes, uuidGenerator, Clock.systemUTC());
    }

    AdminRecoveryCredentialService(
            JdbcClient jdbcClient,
            RecoveryCodeService recoveryCodes,
            UuidV7Generator uuidGenerator,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.recoveryCodes = recoveryCodes;
        this.uuidGenerator = uuidGenerator;
        this.clock = clock;
    }

    @Transactional
    public IssuedCredential issue(
            UUID actorUserId,
            String actorSid,
            UUID targetUserId,
            String reason,
            UUID requestId) {
        TargetState target = lockTarget(actorUserId, targetUserId);
        OffsetDateTime now = now();
        jdbcClient.sql("""
                UPDATE admin_recovery_credentials
                   SET revoked_at = :now
                 WHERE target_user_id = :targetUserId
                   AND used_at IS NULL
                   AND revoked_at IS NULL
                """).param("now", now).param("targetUserId", targetUserId).update();
        RecoveryCodeService.RecoveryCode credential = recoveryCodes.generateAdminCredential();
        OffsetDateTime expiresAt = now.plus(LIFETIME);
        jdbcClient.sql("""
                INSERT INTO admin_recovery_credentials
                    (id, target_user_id, issued_by_user_id, credential_hash,
                     expires_at, reason, created_at)
                VALUES (:id, :targetUserId, :actorUserId, :hash, :expiresAt, :reason, :now)
                """).param("id", uuidGenerator.generate())
                .param("targetUserId", targetUserId)
                .param("actorUserId", actorUserId)
                .param("hash", credential.digest())
                .param("expiresAt", expiresAt)
                .param("reason", reason)
                .param("now", now).update();
        insertSecurityEvent(targetUserId, requestId, now);
        insertAudit(actorUserId, actorSid, targetUserId, expiresAt, reason,
                target.status(), requestId, now);
        return new IssuedCredential(credential.plaintext(), expiresAt, true, false);
    }

    private TargetState lockTarget(UUID actorUserId, UUID targetUserId) {
        if (actorUserId.equals(targetUserId)) {
            throw new AdminUserManagementException("ADMIN_SELF_ACTION_FORBIDDEN");
        }
        TargetState target = jdbcClient.sql("""
                SELECT account_status::text, is_sso_admin
                  FROM users
                 WHERE id = :targetUserId
                 FOR UPDATE
                """).param("targetUserId", targetUserId)
                .query((rs, rowNum) -> new TargetState(
                        rs.getString("account_status"), rs.getBoolean("is_sso_admin")))
                .optional()
                .orElseThrow(() -> new AdminUserManagementException("ADMIN_TARGET_NOT_FOUND"));
        if (target.administrator()) {
            throw new AdminUserManagementException("ADMIN_TARGET_IS_ADMIN");
        }
        if (!"ACTIVE".equals(target.status())) {
            throw new AdminUserManagementException("ACCOUNT_STATE_CONFLICT");
        }
        return target;
    }

    private void insertAudit(
            UUID actorUserId,
            String actorSid,
            UUID targetUserId,
            OffsetDateTime expiresAt,
            String reason,
            String previousStatus,
            UUID requestId,
            OffsetDateTime now) {
        UUID actorSessionId = actorSid == null ? null : jdbcClient.sql("""
                SELECT id FROM user_sessions WHERE sid = :sid AND user_id = :actorUserId
                """).param("sid", actorSid).param("actorUserId", actorUserId)
                .query(UUID.class).optional().orElse(null);
        jdbcClient.sql("""
                INSERT INTO admin_audit_events
                    (id, occurred_at, actor_user_id, actor_session_id, action,
                     target_type, target_id, request_id, result, reason, details_json)
                VALUES (:id, :now, :actorUserId, :actorSessionId, 'ADMIN_RECOVERY_CREDENTIAL_ISSUE',
                        'USER', :targetId, :requestId, 'SUCCESS', :reason,
                        jsonb_build_object('previousStatus', :previousStatus,
                                           'expiresAt', :expiresAt))
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("actorUserId", actorUserId).param("actorSessionId", actorSessionId)
                .param("targetId", targetUserId.toString())
                .param("requestId", requestId).param("reason", reason)
                .param("previousStatus", previousStatus)
                .param("expiresAt", expiresAt.toString()).update();
    }

    private void insertSecurityEvent(UUID userId, UUID requestId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                VALUES (:id, :now, :userId, 'ADMIN_RECOVERY_CREDENTIAL_ISSUED',
                        'HIGH', :requestId, '{}'::jsonb)
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("userId", userId).param("requestId", requestId).update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record IssuedCredential(
            String credential,
            OffsetDateTime expiresAt,
            boolean secretsAvailable,
            boolean idempotentReplay) {
    }

    private record TargetState(String status, boolean administrator) {
    }
}
