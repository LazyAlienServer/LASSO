package org.las.sso.maintenance;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.las.sso.access.ServiceAccessEligibilityService;
import org.las.sso.account.PasswordPolicy;
import org.las.sso.identity.UuidV7Generator;
import org.las.sso.logout.BackChannelLogoutOutbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPasswordResetService {

    private static final long ADVISORY_LOCK_KEY = 5_492_486_017_847_824_976L;

    private final JdbcClient jdbcClient;
    private final UuidV7Generator uuidGenerator;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final BackChannelLogoutOutbox backChannelLogout;
    private final ServiceAccessEligibilityService serviceAccess;
    private final Clock clock;

    @Autowired
    public AdminPasswordResetService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            BackChannelLogoutOutbox backChannelLogout,
            ServiceAccessEligibilityService serviceAccess) {
        this(jdbcClient, uuidGenerator, passwordEncoder, passwordPolicy,
                backChannelLogout, serviceAccess, Clock.systemUTC());
    }

    AdminPasswordResetService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            BackChannelLogoutOutbox backChannelLogout,
            ServiceAccessEligibilityService serviceAccess,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.uuidGenerator = uuidGenerator;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.backChannelLogout = backChannelLogout;
        this.serviceAccess = serviceAccess;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Administrator currentAdministrator() {
        LockedAdministrator administrator = loadAdministrator(false);
        return new Administrator(administrator.userId(), administrator.username());
    }

    @Transactional
    public ResetResult resetPassword(UUID expectedUserId, String newPassword) {
        acquireLock();
        LockedAdministrator administrator = loadAdministrator(true);
        if (!administrator.userId().equals(expectedUserId)) {
            throw new AdminPasswordResetException("ADMIN_CHANGED_DURING_CONFIRMATION");
        }
        passwordPolicy.validate(administrator.username(), newPassword);
        if (passwordEncoder.matches(newPassword, administrator.passwordHash())) {
            throw new AdminPasswordResetException("NEW_PASSWORD_REUSED");
        }

        OffsetDateTime now = now();
        jdbcClient.sql("""
                UPDATE password_credentials
                   SET password_hash = :passwordHash,
                       state = 'ACTIVE',
                       credentials_version = credentials_version + 1,
                       password_changed_at = :now,
                       temporary_expires_at = NULL,
                       failed_attempts = 0,
                       last_failed_at = NULL,
                       locked_until = NULL
                 WHERE user_id = :userId
                """).param("passwordHash", passwordEncoder.encode(newPassword))
                .param("now", now).param("userId", administrator.userId()).update();
        serviceAccess.advanceStateVersion(administrator.userId(), now);
        revokeSecurityState(administrator.userId(), now);
        UUID auditEventId = insertAudit(administrator.userId(), now);
        insertSecurityEvent(administrator.userId(), now);
        insertGlobalLogout(administrator.userId(), now);
        return new ResetResult(administrator.userId(), administrator.username(), auditEventId);
    }

    private LockedAdministrator loadAdministrator(boolean forUpdate) {
        if (forUpdate) {
            // Serialize with recovery before acquiring username/credential locks.
            jdbcClient.sql("SELECT id FROM users WHERE is_sso_admin = true FOR UPDATE")
                    .query(UUID.class).list();
        }
        String locking = forUpdate ? " FOR UPDATE OF un, pc" : "";
        var administrators = jdbcClient.sql("""
                SELECT u.id, u.account_status::text, un.username, pc.password_hash
                  FROM users u
                  JOIN usernames un ON un.user_id = u.id AND un.valid_to IS NULL
                  JOIN password_credentials pc ON pc.user_id = u.id
                 WHERE u.is_sso_admin = true
                """ + locking).query((rs, rowNum) -> new LockedAdministrator(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("account_status"),
                        rs.getString("password_hash")))
                .list();
        if (administrators.isEmpty()) {
            throw new AdminPasswordResetException("ADMIN_NOT_FOUND");
        }
        if (administrators.size() != 1) {
            throw new AdminPasswordResetException("ADMIN_STATE_INVALID");
        }
        LockedAdministrator administrator = administrators.getFirst();
        if (!"ACTIVE".equals(administrator.accountStatus())) {
            throw new AdminPasswordResetException("ADMIN_NOT_ACTIVE");
        }
        return administrator;
    }

    private void acquireLock() {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", ADVISORY_LOCK_KEY)
                .query((rs, rowNum) -> 0)
                .single();
    }

    private void revokeSecurityState(UUID userId, OffsetDateTime now) {
        jdbcClient.sql("""
                UPDATE recovery_codes SET revoked_at = :now
                 WHERE user_id = :userId AND revoked_at IS NULL
                """).param("now", now).param("userId", userId).update();
        jdbcClient.sql("""
                UPDATE recovery_sessions SET consumed_at = :now
                 WHERE user_id = :userId AND consumed_at IS NULL
                """).param("now", now).param("userId", userId).update();
        jdbcClient.sql("""
                UPDATE admin_recovery_credentials SET revoked_at = :now
                 WHERE target_user_id = :userId AND revoked_at IS NULL
                """).param("now", now).param("userId", userId).update();
        jdbcClient.sql("""
                UPDATE user_sessions
                   SET status = 'REVOKED', revoked_at = :now,
                       revoke_reason = 'ADMIN_PASSWORD_RESET'
                 WHERE user_id = :userId AND status = 'ACTIVE'
                """).param("now", now).param("userId", userId).update();
        backChannelLogout.enqueueForUser(userId, "ADMIN_PASSWORD_RESET", now);
        jdbcClient.sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                .param("principalName", userId.toString()).update();
    }

    private UUID insertAudit(UUID userId, OffsetDateTime now) {
        UUID auditEventId = uuidGenerator.generate();
        jdbcClient.sql("""
                INSERT INTO admin_audit_events
                    (id, occurred_at, action, target_type, target_id, request_id, result, reason)
                VALUES (:id, :now, 'ADMIN_PASSWORD_RESET_BY_MAINTENANCE',
                        'USER', :targetId, :requestId, 'SUCCESS',
                        'SERVER_MAINTENANCE_COMMAND')
                """).param("id", auditEventId).param("now", now)
                .param("targetId", userId.toString())
                .param("requestId", uuidGenerator.generate()).update();
        return auditEventId;
    }

    private void insertSecurityEvent(UUID userId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                VALUES (:id, :now, :userId, 'ADMIN_PASSWORD_RESET_BY_MAINTENANCE',
                        'WARNING', :requestId, '{}'::jsonb)
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("userId", userId).param("requestId", uuidGenerator.generate()).update();
    }

    private void insertGlobalLogout(UUID userId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload_json,
                     created_at, available_at)
                VALUES (:id, 'USER', :userId, 'GLOBAL_LOGOUT_REQUESTED',
                        jsonb_build_object('userId', :userId,
                                           'reason', 'ADMIN_PASSWORD_RESET'),
                        :now, :now)
                """).param("id", uuidGenerator.generate())
                .param("userId", userId.toString()).param("now", now).update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record Administrator(UUID userId, String username) {
    }

    private record LockedAdministrator(
            UUID userId, String username, String accountStatus, String passwordHash) {
    }

    public record ResetResult(UUID userId, String username, UUID auditEventId) {
    }
}
