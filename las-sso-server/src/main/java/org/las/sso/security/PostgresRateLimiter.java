package org.las.sso.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostgresRateLimiter {

    private final JdbcClient jdbcClient;
    private final TransientSecurityKeyHasher keyHasher;
    private final Clock clock;

    @Autowired
    public PostgresRateLimiter(JdbcClient jdbcClient, TransientSecurityKeyHasher keyHasher) {
        this(jdbcClient, keyHasher, Clock.systemUTC());
    }

    PostgresRateLimiter(JdbcClient jdbcClient, TransientSecurityKeyHasher keyHasher, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.keyHasher = keyHasher;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision consume(String scope, String rawKey, int limit, Duration window) {
        Window currentWindow = currentWindow(scope, limit, window);
        OffsetDateTime windowStart = currentWindow.startsAt();
        OffsetDateTime expiresAt = currentWindow.expiresAt();
        Instant nowInstant = clock.instant();
        OffsetDateTime now = OffsetDateTime.ofInstant(nowInstant, ZoneOffset.UTC);
        byte[] keyHash = keyHasher.hash("rate:" + scope, rawKey);

        int count =
                jdbcClient
                        .sql(
                                """
                INSERT INTO rate_limit_buckets
                    (scope, key_hash, window_start, window_expires_at, attempt_count, updated_at)
                VALUES (:scope, :keyHash, :windowStart, :expiresAt, 1, :now)
                ON CONFLICT (scope, key_hash, window_start) DO UPDATE
                   SET attempt_count = rate_limit_buckets.attempt_count + 1,
                       updated_at = EXCLUDED.updated_at
                RETURNING attempt_count
                """)
                        .param("scope", scope)
                        .param("keyHash", keyHash)
                        .param("windowStart", windowStart)
                        .param("expiresAt", expiresAt)
                        .param("now", now)
                        .query(Integer.class)
                        .single();
        return new Decision(count <= limit, count, limit, expiresAt);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Observation current(String scope, String rawKey, int limit, Duration window) {
        Window currentWindow = currentWindow(scope, limit, window);
        byte[] keyHash = keyHasher.hash("rate:" + scope, rawKey);
        int count =
                jdbcClient
                        .sql(
                                """
                SELECT attempt_count
                  FROM rate_limit_buckets
                 WHERE scope = :scope
                   AND key_hash = :keyHash
                   AND window_start = :windowStart
                """)
                        .param("scope", scope)
                        .param("keyHash", keyHash)
                        .param("windowStart", currentWindow.startsAt())
                        .query(Integer.class)
                        .optional()
                        .orElse(0);
        return new Observation(count, limit, currentWindow.expiresAt());
    }

    private Window currentWindow(String scope, int limit, Duration window) {
        if (scope == null
                || scope.isBlank()
                || scope.length() > 64
                || limit <= 0
                || window == null
                || window.isZero()
                || window.isNegative()) {
            throw new IllegalArgumentException("Invalid rate limit definition");
        }
        long windowSeconds = window.toSeconds();
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("Rate limit window must be at least one second");
        }
        long startEpoch =
                Math.floorDiv(clock.instant().getEpochSecond(), windowSeconds) * windowSeconds;
        OffsetDateTime startsAt =
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(startEpoch), ZoneOffset.UTC);
        return new Window(startsAt, startsAt.plusSeconds(windowSeconds));
    }

    public record Decision(boolean allowed, int count, int limit, OffsetDateTime resetsAt) {}

    public record Observation(int count, int limit, OffsetDateTime resetsAt) {

        public boolean thresholdReached() {
            return count >= limit;
        }
    }

    private record Window(OffsetDateTime startsAt, OffsetDateTime expiresAt) {}
}
