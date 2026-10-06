package org.las.sso.security;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.las.sso.config.FirstPartyAuthenticationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent pre-hash failure/in-flight budget for internal Basic, not an all-query rate quota. */
@Service
public class FirstPartyAuthenticationBudget {

    static final String NETWORK_SCOPE = "FIRST_PARTY_AUTH_NETWORK";
    static final String CLIENT_SCOPE = "FIRST_PARTY_AUTH_CLIENT";

    private final JdbcClient jdbc;
    private final TransientSecurityKeyHasher hasher;
    private final FirstPartyAuthenticationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public FirstPartyAuthenticationBudget(JdbcClient jdbc, TransientSecurityKeyHasher hasher,
            FirstPartyAuthenticationProperties properties, PlatformTransactionManager transactionManager) {
        this(jdbc, hasher, properties, transactionManager, Clock.systemUTC());
    }

    FirstPartyAuthenticationBudget(JdbcClient jdbc, TransientSecurityKeyHasher hasher,
            FirstPartyAuthenticationProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Reservation reserve(String clientId, String networkKey) {
        Instant now = clock.instant();
        long seconds = properties.window().toSeconds();
        Instant start = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
        OffsetDateTime windowStart = start.atOffset(ZoneOffset.UTC);
        OffsetDateTime resetsAt = start.plusSeconds(seconds).atOffset(ZoneOffset.UTC);
        // All acquisition/refund paths take network before client. Hashing runs after commit, without row locks.
        var buckets = List.of(bucket(NETWORK_SCOPE, networkKey, properties.networkFailures()),
                bucket(CLIENT_SCOPE, clientId, properties.clientFailures()));
        transactions.executeWithoutResult(status -> {
            for (Bucket bucket : buckets) {
                var admitted = jdbc.sql("""
                        INSERT INTO rate_limit_buckets
                            (scope, key_hash, window_start, window_expires_at, attempt_count, updated_at)
                        VALUES (:scope, :key, :start, :expires, 1, :now)
                        ON CONFLICT (scope, key_hash, window_start) DO UPDATE
                           SET attempt_count = rate_limit_buckets.attempt_count + 1,
                               updated_at = EXCLUDED.updated_at
                         WHERE rate_limit_buckets.attempt_count < :limit
                        RETURNING attempt_count
                        """).param("scope", bucket.scope()).param("key", bucket.key())
                        .param("start", windowStart).param("expires", resetsAt)
                        .param("now", now.atOffset(ZoneOffset.UTC)).param("limit", bucket.limit())
                        .query(Integer.class).optional();
                if (admitted.isEmpty()) throw new RateLimitExceededException(resetsAt);
            }
        });
        return new Reservation(buckets, windowStart);
    }

    /** Call only on authenticated success. Failure/crash reservations deliberately expire with their window. */
    public void release(Reservation reservation) {
        synchronized (reservation) {
            if (reservation.released) return;
            transactions.executeWithoutResult(status -> {
                for (Bucket bucket : reservation.buckets) {
                    var count = jdbc.sql("""
                            SELECT attempt_count FROM rate_limit_buckets
                             WHERE scope = :scope AND key_hash = :key AND window_start = :start
                             FOR UPDATE
                            """).param("scope", bucket.scope()).param("key", bucket.key())
                            .param("start", reservation.windowStart).query(Integer.class).optional();
                    if (count.isEmpty()) continue; // Expiry cleanup may already have removed the old window.
                    if (count.get() == 1) {
                        jdbc.sql("""
                                DELETE FROM rate_limit_buckets
                                 WHERE scope = :scope AND key_hash = :key AND window_start = :start
                                """).param("scope", bucket.scope()).param("key", bucket.key())
                                .param("start", reservation.windowStart).update();
                    } else {
                        jdbc.sql("""
                                UPDATE rate_limit_buckets
                                   SET attempt_count = attempt_count - 1, updated_at = :now
                                 WHERE scope = :scope AND key_hash = :key AND window_start = :start
                                """).param("scope", bucket.scope()).param("key", bucket.key())
                                .param("start", reservation.windowStart)
                                .param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
                    }
                }
            });
            reservation.released = true;
        }
    }

    private Bucket bucket(String scope, String key, int limit) {
        return new Bucket(scope, hasher.hash("rate:" + scope, key), limit);
    }

    /** Opaque request-local receipt: no credentials/raw address, not a durable lease or shared-store token. */
    public static final class Reservation {
        private final List<Bucket> buckets;
        private final OffsetDateTime windowStart;
        private boolean released;

        private Reservation(List<Bucket> buckets, OffsetDateTime windowStart) {
            this.buckets = buckets;
            this.windowStart = windowStart;
        }
    }

    private record Bucket(String scope, byte[] key, int limit) { }
}
