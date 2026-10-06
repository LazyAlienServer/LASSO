package org.las.sso.account;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.las.sso.access.ServiceAccessEligibilityService;
import org.las.sso.identity.UuidV7Generator;
import org.las.sso.logout.BackChannelLogoutOutbox;
import org.las.sso.security.AltchaChallengeService;
import org.las.sso.security.PostgresRateLimiter;
import org.las.sso.security.RateLimitExceededException;
import org.las.sso.security.TransientSecurityKeyHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordRecoveryService {

    public static final Duration SESSION_LIFETIME = Duration.ofMinutes(10);

    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final int USER_ATTEMPT_LIMIT = 5;
    private static final int IP_ATTEMPT_LIMIT = 20;
    private static final int GLOBAL_ATTEMPT_LIMIT = 1_000;
    private static final int USER_CAPTCHA_THRESHOLD = 3;
    private static final int IP_CAPTCHA_THRESHOLD = 10;

    private final JdbcClient jdbcClient;
    private final RecoveryCodeService recoveryCodes;
    private final TransientSecurityKeyHasher keyHasher;
    private final PostgresRateLimiter rateLimiter;
    private final AltchaChallengeService captcha;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final UuidV7Generator uuidGenerator;
    private final BackChannelLogoutOutbox backChannelLogout;
    private final ServiceAccessEligibilityService serviceAccess;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final AdminRecoveryFailureAuditService failureAudit;

    @Autowired
    public PasswordRecoveryService(
            JdbcClient jdbcClient,
            RecoveryCodeService recoveryCodes,
            TransientSecurityKeyHasher keyHasher,
            PostgresRateLimiter rateLimiter,
            AltchaChallengeService captcha,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            UuidV7Generator uuidGenerator,
            BackChannelLogoutOutbox backChannelLogout,
            ServiceAccessEligibilityService serviceAccess,
            AdminRecoveryFailureAuditService failureAudit) {
        this(jdbcClient, recoveryCodes, keyHasher, rateLimiter, captcha, passwordEncoder,
                passwordPolicy, uuidGenerator, backChannelLogout,
                serviceAccess, new SecureRandom(), Clock.systemUTC(), failureAudit);
    }

    PasswordRecoveryService(
            JdbcClient jdbcClient,
            RecoveryCodeService recoveryCodes,
            TransientSecurityKeyHasher keyHasher,
            PostgresRateLimiter rateLimiter,
            AltchaChallengeService captcha,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            UuidV7Generator uuidGenerator,
            BackChannelLogoutOutbox backChannelLogout,
            ServiceAccessEligibilityService serviceAccess,
            SecureRandom secureRandom,
            Clock clock,
            AdminRecoveryFailureAuditService failureAudit) {
        this.jdbcClient = jdbcClient;
        this.recoveryCodes = recoveryCodes;
        this.keyHasher = keyHasher;
        this.rateLimiter = rateLimiter;
        this.captcha = captcha;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.uuidGenerator = uuidGenerator;
        this.backChannelLogout = backChannelLogout;
        this.serviceAccess = serviceAccess;
        this.secureRandom = secureRandom;
        this.clock = clock;
        this.failureAudit = failureAudit;
    }

    @Transactional
    public RecoverySession start(
            String username,
            String credential,
            CredentialType credentialType,
            CaptchaSubmission captchaSubmission,
            String networkKey) {
        return start(username, credential, credentialType, captchaSubmission,
                networkKey, uuidGenerator.generate());
    }

    @Transactional
    public RecoverySession start(
            String username,
            String credential,
            CredentialType credentialType,
            CaptchaSubmission captchaSubmission,
            String networkKey,
            UUID requestId) {
        var userDecision = consume("RECOVERY_USER", username, USER_ATTEMPT_LIMIT);
        var ipDecision = consume("RECOVERY_IP", networkKey, IP_ATTEMPT_LIMIT);
        consume("RECOVERY_GLOBAL", "all", GLOBAL_ATTEMPT_LIMIT);

        boolean captchaRequired = userDecision.count() > USER_CAPTCHA_THRESHOLD
                || ipDecision.count() > IP_CAPTCHA_THRESHOLD;
        if (captchaRequired) {
            if (captchaSubmission == null) {
                throw new RecoveryFlowException("CAPTCHA_REQUIRED");
            }
            captcha.verifyAndConsume(
                    captchaSubmission.challengeId(),
                    AltchaChallengeService.Purpose.RECOVERY_RISK,
                    captchaSubmission.payload(),
                    networkKey);
        }

        ConsumedCredential consumed;
        try {
            // Resolve only a candidate here; ownership/name/credential validity is rechecked after locking.
            UUID candidateUser = jdbcClient.sql("""
                    SELECT user_id FROM usernames WHERE username = :username COLLATE "C"
                        AND valid_to IS NULL
                    """).param("username", username).query(UUID.class).optional()
                    .orElseThrow(() -> new RecoveryFlowException("RECOVERY_CREDENTIAL_INVALID"));
            lockActiveUser(candidateUser, "RECOVERY_CREDENTIAL_INVALID");
            OffsetDateTime now = now();
            consumed = consumeCredential(candidateUser, username, credential, credentialType, now);
        } catch (RecoveryFlowException exception) {
            if (credentialType == CredentialType.ADMIN_ISSUED) {
                failureAudit.recordRejectedAttempt(
                        username, recoveryCodes.digest(credential), requestId, now());
            }
            throw exception;
        }
        UUID userId = consumed.userId();
        OffsetDateTime now = now();
        String token = newSessionToken();
        jdbcClient.sql("""
                INSERT INTO recovery_sessions
                    (id, user_id, session_token_hash, credential_type, created_at, expires_at)
                VALUES (:id, :userId, :tokenHash, :credentialType, :now, :expiresAt)
                """).param("id", uuidGenerator.generate()).param("userId", userId)
                .param("tokenHash", keyHasher.hash("recovery-session", token))
                .param("credentialType", credentialType.name()).param("now", now)
                .param("expiresAt", now.plus(SESSION_LIFETIME)).update();
        securityEvent(userId, "RECOVERY_SESSION_CREATED", "INFO", requestId, now);
        if (consumed.adminCredentialId() != null) {
            adminRecoveryAudit(
                    consumed.issuedByUserId(), userId, consumed.adminCredentialId(),
                    "ADMIN_RECOVERY_CREDENTIAL_USED", "SUCCESS", requestId, now);
        }
        return new RecoverySession(token, now.plus(SESSION_LIFETIME));
    }

    @Transactional
    public void complete(String sessionToken, String newPassword) {
        complete(sessionToken, newPassword, uuidGenerator.generate());
    }

    @Transactional
    public void complete(String sessionToken, String newPassword, UUID requestId) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new RecoveryFlowException("SESSION_EXPIRED");
        }
        byte[] tokenHash = keyHasher.hash("recovery-session", sessionToken);
        // Never lock a recovery-session row before its user: password change holds user first.
        UUID candidateUser = jdbcClient.sql("""
                SELECT user_id FROM recovery_sessions WHERE session_token_hash = :tokenHash
                """).param("tokenHash", tokenHash).query(UUID.class).optional()
                .orElseThrow(() -> new RecoveryFlowException("SESSION_EXPIRED"));
        lockActiveUser(candidateUser, "SESSION_EXPIRED");
        OffsetDateTime now = now();
        RecoveryCredential credential = jdbcClient.sql("""
                SELECT rs.id AS recovery_session_id,
                       rs.user_id,
                       un.username,
                       pc.password_hash,
                       pc.state
                  FROM recovery_sessions rs
                  JOIN users u ON u.id = rs.user_id
                  JOIN usernames un ON un.user_id = u.id AND un.valid_to IS NULL
                  JOIN password_credentials pc ON pc.user_id = u.id
                 WHERE rs.session_token_hash = :tokenHash
                   AND rs.consumed_at IS NULL
                   AND rs.expires_at > :now
                   AND u.account_status = 'ACTIVE'
                    AND rs.user_id = :userId
                  FOR UPDATE OF rs, un, pc
                """).param("tokenHash", tokenHash).param("userId", candidateUser)
                .param("now", now)
                .query((rs, rowNum) -> new RecoveryCredential(
                        rs.getObject("recovery_session_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("state")))
                .optional()
                .orElseThrow(() -> new RecoveryFlowException("SESSION_EXPIRED"));

        try {
            passwordPolicy.validate(credential.username(), newPassword);
        } catch (PasswordPolicyException exception) {
            throw new RecoveryFlowException(exception.code());
        }
        if (passwordEncoder.matches(newPassword, credential.passwordHash())) {
            throw new RecoveryFlowException("NEW_PASSWORD_REUSED");
        }

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
                .param("now", now).param("userId", credential.userId()).update();
        if ("PASSWORD_CHANGE_REQUIRED".equals(credential.credentialState())) {
            serviceAccess.advanceStateVersion(credential.userId(), now);
        }
        jdbcClient.sql("""
                UPDATE recovery_sessions
                   SET consumed_at = :now
                 WHERE user_id = :userId AND consumed_at IS NULL
                """).param("now", now).param("userId", credential.userId()).update();
        List<AdminCredentialAuditTarget> revokedAdminCredentials = jdbcClient.sql("""
                SELECT id, issued_by_user_id
                  FROM admin_recovery_credentials
                 WHERE target_user_id = :userId
                   AND used_at IS NULL AND revoked_at IS NULL
                """).param("userId", credential.userId())
                .query((rs, rowNum) -> new AdminCredentialAuditTarget(
                        rs.getObject("id", UUID.class),
                        rs.getObject("issued_by_user_id", UUID.class))).list();
        jdbcClient.sql("""
                UPDATE admin_recovery_credentials SET revoked_at = :now
                 WHERE target_user_id = :userId AND revoked_at IS NULL
                """).param("now", now).param("userId", credential.userId()).update();
        for (AdminCredentialAuditTarget revoked : revokedAdminCredentials) {
            adminRecoveryAudit(
                    revoked.issuedByUserId(), credential.userId(), revoked.id(),
                    "ADMIN_RECOVERY_CREDENTIAL_REVOKED", "SUCCESS", requestId, now);
        }
        jdbcClient.sql("""
                UPDATE user_sessions
                   SET status = 'REVOKED', revoked_at = :now, revoke_reason = 'PASSWORD_RECOVERED'
                 WHERE user_id = :userId AND status = 'ACTIVE'
                """).param("now", now).param("userId", credential.userId()).update();
        backChannelLogout.enqueueForUser(credential.userId(), "PASSWORD_RECOVERED", now);
        jdbcClient.sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                .param("principalName", credential.userId().toString()).update();
        securityEvent(credential.userId(), "PASSWORD_RECOVERED", "WARNING", requestId, now);
        jdbcClient.sql("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload_json,
                     created_at, available_at)
                VALUES (:id, 'USER', :userId, 'GLOBAL_LOGOUT_REQUESTED',
                        jsonb_build_object('userId', :userId, 'reason', 'PASSWORD_RECOVERED'),
                        :now, :now)
                """).param("id", uuidGenerator.generate())
                .param("userId", credential.userId().toString()).param("now", now).update();
    }

    private ConsumedCredential consumeCredential(
            UUID candidateUser, String username, String credential,
            CredentialType credentialType, OffsetDateTime now) {
        byte[] digest = recoveryCodes.digest(credential);
        if (credentialType == CredentialType.RECOVERY_CODE) {
            UUID userId = jdbcClient.sql("""
                    UPDATE recovery_codes rc
                       SET used_at = :now
                      FROM users u, usernames un
                      WHERE rc.user_id = u.id
                        AND u.id = :userId
                       AND un.user_id = u.id
                       AND un.valid_to IS NULL
                       AND un.username = :username COLLATE "C"
                       AND u.account_status = 'ACTIVE'
                       AND rc.code_hash = :digest
                       AND rc.used_at IS NULL
                       AND rc.revoked_at IS NULL
                    RETURNING rc.user_id
                    """).param("now", now).param("userId", candidateUser)
                    .param("username", username).param("digest", digest)
                    .query(UUID.class).optional()
                    .orElseThrow(() -> new RecoveryFlowException("RECOVERY_CREDENTIAL_INVALID"));
            return new ConsumedCredential(userId, null, null);
        }
        return jdbcClient.sql("""
                UPDATE admin_recovery_credentials arc
                   SET used_at = :now
                  FROM users u, usernames un
                  WHERE arc.target_user_id = u.id
                    AND u.id = :userId
                   AND un.user_id = u.id
                   AND un.valid_to IS NULL
                   AND un.username = :username COLLATE "C"
                   AND u.account_status = 'ACTIVE'
                   AND arc.credential_hash = :digest
                   AND arc.used_at IS NULL
                   AND arc.revoked_at IS NULL
                   AND arc.expires_at > :now
                RETURNING arc.target_user_id, arc.id, arc.issued_by_user_id
                """).param("now", now).param("userId", candidateUser)
                .param("username", username).param("digest", digest)
                .query((rs, rowNum) -> new ConsumedCredential(
                        rs.getObject("target_user_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getObject("issued_by_user_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new RecoveryFlowException("RECOVERY_CREDENTIAL_INVALID"));
    }

    private void lockActiveUser(UUID userId, String failureCode) {
        jdbcClient.sql("SELECT id FROM users WHERE id = :userId AND account_status = 'ACTIVE' FOR UPDATE")
                .param("userId", userId).query(UUID.class).optional()
                .orElseThrow(() -> new RecoveryFlowException(failureCode));
    }

    private void adminRecoveryAudit(
            UUID actorUserId,
            UUID targetUserId,
            UUID credentialId,
            String action,
            String result,
            UUID requestId,
            OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO admin_audit_events
                    (id, occurred_at, actor_user_id, action, target_type, target_id,
                     request_id, result, details_json)
                VALUES (:id, :now, :actorUserId, :action, 'USER', :targetId,
                        :requestId, :result,
                        jsonb_build_object('credentialId', :credentialId))
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("actorUserId", actorUserId).param("action", action)
                .param("targetId", targetUserId.toString()).param("requestId", requestId)
                .param("result", result).param("credentialId", credentialId.toString()).update();
    }

    private PostgresRateLimiter.Decision consume(String scope, String key, int limit) {
        PostgresRateLimiter.Decision decision = rateLimiter.consume(scope, key, limit, ATTEMPT_WINDOW);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.resetsAt());
        }
        return decision;
    }

    private void securityEvent(
            UUID userId,
            String eventType,
            String severity,
            UUID requestId,
            OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                VALUES (:id, :now, :userId, :eventType, :severity, :requestId, '{}'::jsonb)
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("userId", userId).param("eventType", eventType).param("severity", severity)
                .param("requestId", requestId).update();
    }

    private String newSessionToken() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public enum CredentialType {
        RECOVERY_CODE,
        ADMIN_ISSUED
    }

    public record CaptchaSubmission(UUID challengeId, String payload) {
    }

    public record RecoverySession(String token, OffsetDateTime expiresAt) {
    }

    private record RecoveryCredential(
            UUID sessionId, UUID userId, String username, String passwordHash,
            String credentialState) {
    }

    private record ConsumedCredential(
            UUID userId, UUID adminCredentialId, UUID issuedByUserId) {
    }

    private record AdminCredentialAuditTarget(UUID id, UUID issuedByUserId) {
    }
}
