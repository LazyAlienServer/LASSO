package org.las.sso.account;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.las.sso.access.ServiceAccessEligibilityService;
import org.las.sso.identity.UuidV7Generator;
import org.las.sso.logout.BackChannelLogoutOutbox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordChangeService {

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final UuidV7Generator uuidGenerator;
    private final BackChannelLogoutOutbox backChannelLogout;
    private final ServiceAccessEligibilityService serviceAccess;
    private final Clock clock;

    @Autowired
    public PasswordChangeService(
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            UuidV7Generator uuidGenerator,
            BackChannelLogoutOutbox backChannelLogout,
            ServiceAccessEligibilityService serviceAccess) {
        this(
                jdbcClient,
                passwordEncoder,
                passwordPolicy,
                uuidGenerator,
                backChannelLogout,
                serviceAccess,
                Clock.systemUTC());
    }

    PasswordChangeService(
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            UuidV7Generator uuidGenerator,
            BackChannelLogoutOutbox backChannelLogout,
            ServiceAccessEligibilityService serviceAccess,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.uuidGenerator = uuidGenerator;
        this.backChannelLogout = backChannelLogout;
        this.serviceAccess = serviceAccess;
        this.clock = clock;
    }

    @Transactional
    public void changePassword(
            UUID userId, boolean requiredChange, String currentCredential, String newPassword) {
        // The user row is the common serialization boundary for credential and recovery operations.
        jdbcClient
                .sql(
                        "SELECT id FROM users WHERE id = :userId AND account_status = 'ACTIVE' FOR UPDATE")
                .param("userId", userId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new PasswordChangeException("CURRENT_PASSWORD_INVALID"));
        Credential credential =
                jdbcClient
                        .sql(
                                """
                SELECT un.username, pc.password_hash, pc.state, pc.temporary_expires_at
                  FROM users u
                  JOIN usernames un ON un.user_id = u.id AND un.valid_to IS NULL
                  JOIN password_credentials pc ON pc.user_id = u.id
                 WHERE u.id = :userId AND u.account_status = 'ACTIVE'
                  FOR UPDATE OF un, pc
                """)
                        .param("userId", userId)
                        .query(
                                (rs, rowNum) ->
                                        new Credential(
                                                rs.getString("username"),
                                                rs.getString("password_hash"),
                                                rs.getString("state"),
                                                rs.getObject(
                                                        "temporary_expires_at",
                                                        OffsetDateTime.class)))
                        .optional()
                        .orElseThrow(() -> new PasswordChangeException("CURRENT_PASSWORD_INVALID"));

        OffsetDateTime now = now();
        if (requiredChange) {
            if (!"PASSWORD_CHANGE_REQUIRED".equals(credential.state())
                    || credential.temporaryExpiresAt() == null
                    || !credential.temporaryExpiresAt().isAfter(now)) {
                throw new PasswordChangeException("TEMPORARY_PASSWORD_EXPIRED");
            }
        } else if (!"ACTIVE".equals(credential.state())) {
            throw new PasswordChangeException("PASSWORD_CHANGE_REQUIRED");
        }
        if (currentCredential == null
                || !passwordEncoder.matches(currentCredential, credential.passwordHash())) {
            throw new PasswordChangeException("CURRENT_PASSWORD_INVALID");
        }
        passwordPolicy.validate(credential.username(), newPassword);
        if (passwordEncoder.matches(newPassword, credential.passwordHash())) {
            throw new PasswordChangeException("NEW_PASSWORD_REUSED");
        }

        jdbcClient
                .sql(
                        """
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
                """)
                .param("passwordHash", passwordEncoder.encode(newPassword))
                .param("now", now)
                .param("userId", userId)
                .update();
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
        if ("PASSWORD_CHANGE_REQUIRED".equals(credential.state())) {
            serviceAccess.advanceStateVersion(userId, now);
        }
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
                   SET status = 'REVOKED', revoked_at = :now, revoke_reason = 'PASSWORD_CHANGED'
                 WHERE user_id = :userId AND status = 'ACTIVE'
                """)
                .param("now", now)
                .param("userId", userId)
                .update();
        backChannelLogout.enqueueForUser(userId, "PASSWORD_CHANGED", now);
        jdbcClient
                .sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                .param("principalName", userId.toString())
                .update();
        jdbcClient
                .sql(
                        """
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                VALUES (:id, :now, :userId, 'PASSWORD_CHANGED', 'INFO', :requestId, '{}'::jsonb)
                """)
                .param("id", uuidGenerator.generate())
                .param("now", now)
                .param("userId", userId)
                .param("requestId", uuidGenerator.generate())
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record Credential(
            String username,
            String passwordHash,
            String state,
            OffsetDateTime temporaryExpiresAt) {}
}
