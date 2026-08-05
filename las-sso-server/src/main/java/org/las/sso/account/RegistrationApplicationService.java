package org.las.sso.account;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.las.sso.security.AltchaChallengeService;
import org.las.sso.security.PostgresRateLimiter;
import org.las.sso.security.RateLimitExceededException;
import org.las.sso.security.TransientSecurityKeyHasher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationApplicationService {

    private final JdbcClient jdbcClient;
    private final AccountRegistrationService accounts;
    private final AltchaChallengeService captcha;
    private final PostgresRateLimiter rateLimiter;
    private final TransientSecurityKeyHasher keyHasher;

    public RegistrationApplicationService(
            JdbcClient jdbcClient,
            AccountRegistrationService accounts,
            AltchaChallengeService captcha,
            PostgresRateLimiter rateLimiter,
            TransientSecurityKeyHasher keyHasher) {
        this.jdbcClient = jdbcClient;
        this.accounts = accounts;
        this.captcha = captcha;
        this.rateLimiter = rateLimiter;
        this.keyHasher = keyHasher;
    }

    @Transactional
    public RegistrationOutcome register(
            UUID idempotencyKey,
            String username,
            String displayName,
            String password,
            UUID challengeId,
            String captchaPayload,
            String networkKey) {
        byte[] requestHash = keyHasher.hash(
                "registration-request",
                username + "\u0000" + displayName + "\u0000" + password + "\u0000"
                        + challengeId + "\u0000" + captchaPayload);
        long lockId = ByteBuffer.wrap(
                keyHasher.hash("registration-idempotency", idempotencyKey.toString()))
                .getLong();
        jdbcClient.sql("SELECT true FROM pg_advisory_xact_lock(:lockId)")
                .param("lockId", lockId)
                .query(Boolean.class)
                .single();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                DELETE FROM registration_idempotency
                 WHERE idempotency_key = :key AND expires_at <= :now
                """).param("key", idempotencyKey).param("now", now).update();
        Optional<StoredRegistration> existing = findExisting(idempotencyKey);
        if (existing.isPresent()) {
            StoredRegistration stored = existing.orElseThrow();
            if (!MessageDigest.isEqual(stored.requestHash(), requestHash)) {
                throw new IdempotencyConflictException();
            }
            return new RegistrationOutcome(
                    stored.userId(), stored.username(), stored.displayName(), List.of(), true);
        }

        enforceLimit("REGISTER_IP_HOUR", networkKey, 5, Duration.ofHours(1));
        enforceLimit("REGISTER_IP_DAY", networkKey, 20, Duration.ofDays(1));
        enforceLimit("REGISTER_GLOBAL_HOUR", "all", 100, Duration.ofHours(1));
        captcha.verifyAndConsume(
                challengeId, AltchaChallengeService.Purpose.REGISTER, captchaPayload, networkKey);
        AccountRegistrationService.RegistrationResult result =
                accounts.register(username, displayName, password);
        jdbcClient.sql("""
                INSERT INTO registration_idempotency
                    (idempotency_key, request_hash, user_id, created_at, expires_at)
                VALUES (:key, :requestHash, :userId, :now, :expiresAt)
                """).param("key", idempotencyKey).param("requestHash", requestHash)
                .param("userId", result.userId()).param("now", now)
                .param("expiresAt", now.plusHours(24)).update();
        return new RegistrationOutcome(
                result.userId(), result.username(), result.displayName(), result.recoveryCodes(), false);
    }

    private void enforceLimit(String scope, String key, int limit, Duration window) {
        PostgresRateLimiter.Decision decision = rateLimiter.consume(scope, key, limit, window);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.resetsAt());
        }
    }

    private Optional<StoredRegistration> findExisting(UUID idempotencyKey) {
        return jdbcClient.sql("""
                SELECT r.request_hash, u.id, n.username, u.display_name
                  FROM registration_idempotency r
                  JOIN users u ON u.id = r.user_id
                  JOIN usernames n ON n.user_id = u.id AND n.valid_to IS NULL
                 WHERE r.idempotency_key = :key
                """).param("key", idempotencyKey).query((rs, rowNum) -> new StoredRegistration(
                        rs.getBytes("request_hash"),
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"))).optional();
    }

    private record StoredRegistration(
            byte[] requestHash, UUID userId, String username, String displayName) {
    }

    public record RegistrationOutcome(
            UUID userId,
            String username,
            String displayName,
            List<String> recoveryCodes,
            boolean idempotentReplay) {
        public RegistrationOutcome {
            recoveryCodes = List.copyOf(recoveryCodes);
        }
    }
}
