package org.las.sso.maintenance;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Pattern;
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
public class AdminBootstrapService {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,24}$");
    private static final long ADVISORY_LOCK_KEY = 5_492_486_017_847_824_976L;

    private final JdbcClient jdbcClient;
    private final UuidV7Generator uuidGenerator;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final BackChannelLogoutOutbox backChannelLogout;
    private final ServiceAccessEligibilityService serviceAccess;
    private final Clock clock;

    @Autowired
    public AdminBootstrapService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            BackChannelLogoutOutbox backChannelLogout,
            ServiceAccessEligibilityService serviceAccess) {
        this(
                jdbcClient,
                uuidGenerator,
                passwordEncoder,
                passwordPolicy,
                backChannelLogout,
                serviceAccess,
                Clock.systemUTC());
    }

    AdminBootstrapService(
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

    @Transactional
    public BootstrapResult createFirstUser(
            String username, String displayName, String temporaryPassword) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new AdminBootstrapException("USERNAME_INVALID");
        }
        if (displayName == null
                || displayName.isBlank()
                || displayName.codePointCount(0, displayName.length()) > 64) {
            throw new AdminBootstrapException("DISPLAY_NAME_INVALID");
        }
        passwordPolicy.validate(username, temporaryPassword);
        acquireLockAndRequireNoAdmin();
        Long userCount = jdbcClient.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
        if (userCount != 0) {
            throw new AdminBootstrapException("USERS_ALREADY_EXIST");
        }

        UUID userId = uuidGenerator.generate();
        OffsetDateTime now = now();
        OffsetDateTime expiresAt = now.plusHours(24);
        jdbcClient
                .sql(
                        """
                INSERT INTO users
                    (id, display_name, account_status, is_sso_admin, created_at, updated_at)
                VALUES (:id, :displayName, 'ACTIVE', true, :now, :now)
                """)
                .param("id", userId)
                .param("displayName", displayName)
                .param("now", now)
                .update();
        jdbcClient
                .sql(
                        """
                INSERT INTO usernames
                    (id, user_id, username, valid_from, changed_by_user_id, change_reason)
                VALUES (:id, :userId, :username, :now, :userId, 'ADMIN_BOOTSTRAP')
                """)
                .param("id", uuidGenerator.generate())
                .param("userId", userId)
                .param("username", username)
                .param("now", now)
                .update();
        insertTemporaryCredential(userId, temporaryPassword, now, expiresAt);
        UUID auditId = insertAudit(userId, now, "ADMIN_BOOTSTRAP_CREATE");
        return new BootstrapResult(userId, username, expiresAt, auditId);
    }

    @Transactional
    public BootstrapResult promoteExistingUser(UUID userId, String temporaryPassword) {
        acquireLockAndRequireNoAdmin();
        jdbcClient
                .sql(
                        "SELECT id FROM users WHERE id = :userId AND account_status = 'ACTIVE' FOR UPDATE")
                .param("userId", userId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new AdminBootstrapException("USER_NOT_FOUND"));
        // Deletion writers serialize on the same user first. Never promote then cancel its request
        // later.
        var effectiveDeletion =
                jdbcClient
                        .sql(
                                """
                SELECT id FROM account_deletion_requests
                 WHERE user_id = :userId AND status IN ('PENDING', 'HELD') FOR UPDATE
                """)
                        .param("userId", userId)
                        .query(UUID.class)
                        .list();
        if (!effectiveDeletion.isEmpty()) {
            throw new AdminBootstrapException("DELETION_CANCELLATION_REQUIRED");
        }
        String username =
                jdbcClient
                        .sql(
                                """
                SELECT un.username
                  FROM users u
                  JOIN usernames un ON un.user_id = u.id AND un.valid_to IS NULL
                 WHERE u.id = :userId AND u.account_status = 'ACTIVE'
                  FOR UPDATE OF un
                """)
                        .param("userId", userId)
                        .query(String.class)
                        .optional()
                        .orElseThrow(() -> new AdminBootstrapException("USER_NOT_FOUND"));
        passwordPolicy.validate(username, temporaryPassword);

        OffsetDateTime now = now();
        OffsetDateTime expiresAt = now.plusHours(24);
        jdbcClient
                .sql(
                        """
                UPDATE users SET is_sso_admin = true, updated_at = :now WHERE id = :userId
                """)
                .param("now", now)
                .param("userId", userId)
                .update();
        jdbcClient
                .sql(
                        """
                UPDATE password_credentials
                   SET password_hash = :passwordHash,
                       state = 'PASSWORD_CHANGE_REQUIRED',
                       credentials_version = credentials_version + 1,
                       password_changed_at = :now,
                       temporary_expires_at = :expiresAt,
                       failed_attempts = 0,
                       locked_until = NULL
                 WHERE user_id = :userId
                """)
                .param("passwordHash", passwordEncoder.encode(temporaryPassword))
                .param("now", now)
                .param("expiresAt", expiresAt)
                .param("userId", userId)
                .update();
        serviceAccess.advanceStateVersion(userId, now);
        revokeExistingSecurityState(userId, now);
        UUID auditId = insertAudit(userId, now, "ADMIN_BOOTSTRAP_PROMOTE");
        return new BootstrapResult(userId, username, expiresAt, auditId);
    }

    private void acquireLockAndRequireNoAdmin() {
        jdbcClient
                .sql("SELECT pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", ADVISORY_LOCK_KEY)
                .query((rs, rowNum) -> 0)
                .single();
        Long adminCount =
                jdbcClient
                        .sql("SELECT COUNT(*) FROM users WHERE is_sso_admin = true")
                        .query(Long.class)
                        .single();
        if (adminCount != 0) {
            throw new AdminBootstrapException("ADMIN_ALREADY_EXISTS");
        }
    }

    private void insertTemporaryCredential(
            UUID userId, String temporaryPassword, OffsetDateTime now, OffsetDateTime expiresAt) {
        jdbcClient
                .sql(
                        """
                INSERT INTO password_credentials
                    (user_id, password_hash, state, credentials_version,
                     password_changed_at, temporary_expires_at)
                VALUES (:userId, :passwordHash, 'PASSWORD_CHANGE_REQUIRED', 1, :now, :expiresAt)
                """)
                .param("userId", userId)
                .param("passwordHash", passwordEncoder.encode(temporaryPassword))
                .param("now", now)
                .param("expiresAt", expiresAt)
                .update();
    }

    private void revokeExistingSecurityState(UUID userId, OffsetDateTime now) {
        jdbcClient
                .sql(
                        """
                UPDATE recovery_codes SET revoked_at = :now
                 WHERE user_id = :userId AND revoked_at IS NULL
                """)
                .param("now", now)
                .param("userId", userId)
                .update();
        jdbcClient
                .sql(
                        """
                UPDATE recovery_sessions SET consumed_at = :now
                 WHERE user_id = :userId AND consumed_at IS NULL
                """)
                .param("now", now)
                .param("userId", userId)
                .update();
        jdbcClient
                .sql(
                        """
                UPDATE admin_recovery_credentials SET revoked_at = :now
                 WHERE target_user_id = :userId AND revoked_at IS NULL
                """)
                .param("now", now)
                .param("userId", userId)
                .update();
        jdbcClient
                .sql(
                        """
                UPDATE user_sessions
                   SET status = 'REVOKED', revoked_at = :now, revoke_reason = 'ADMIN_BOOTSTRAP'
                 WHERE user_id = :userId AND status = 'ACTIVE'
                """)
                .param("now", now)
                .param("userId", userId)
                .update();
        backChannelLogout.enqueueForUser(userId, "ADMIN_BOOTSTRAP", now);
        jdbcClient
                .sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                .param("principalName", userId.toString())
                .update();
    }

    private UUID insertAudit(UUID userId, OffsetDateTime now, String action) {
        UUID auditId = uuidGenerator.generate();
        jdbcClient
                .sql(
                        """
                INSERT INTO admin_audit_events
                    (id, occurred_at, action, target_type, target_id, request_id, result, reason)
                VALUES (:id, :now, :action, 'USER', :targetId, :requestId,
                        'SUCCESS', 'SERVER_MAINTENANCE_COMMAND')
                """)
                .param("id", auditId)
                .param("now", now)
                .param("action", action)
                .param("targetId", userId.toString())
                .param("requestId", uuidGenerator.generate())
                .update();
        return auditId;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record BootstrapResult(
            UUID userId,
            String username,
            OffsetDateTime temporaryPasswordExpiresAt,
            UUID auditEventId) {}
}
