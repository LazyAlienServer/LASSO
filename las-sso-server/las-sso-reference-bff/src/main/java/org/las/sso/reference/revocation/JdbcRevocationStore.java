package org.las.sso.reference.revocation;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reference schema only. Every short write commits independently of a caller's transaction. */
final class JdbcRevocationStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    JdbcRevocationStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void insert(RevocationTask task) {
        transactions.executeWithoutResult(status -> jdbc.sql("""
                INSERT INTO reference_revocation.tasks
                    (id, registration_id, issuer, client_id, revocation_uri, token_type_hint, key_id, nonce, ciphertext)
                VALUES (:id, :registration, :issuer, :client, :uri, :hint, :key, :nonce, :ciphertext)
                """).param("id", task.id()).param("registration", task.registrationId()).param("issuer", task.issuer())
                .param("client", task.clientId()).param("uri", task.uri()).param("hint", task.hint())
                .param("key", task.keyId()).param("nonce", task.nonce()).param("ciphertext", task.ciphertext()).update());
    }

    Optional<RevocationTask> claim(Duration lease) {
        return transactions.execute(status -> {
            // A crash on the eighth claim must not leave an immortal pending task or a ninth HTTP attempt.
            jdbc.sql("""
                    UPDATE reference_revocation.tasks
                       SET dead_lettered_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_until = NULL,
                           last_error_code = 'LEASE_EXHAUSTED'
                     WHERE completed_at IS NULL AND dead_lettered_at IS NULL AND attempt_count >= 8
                       AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    """).update();
            return jdbc.sql("""
                    WITH candidate AS (
                        SELECT id FROM reference_revocation.tasks
                         WHERE completed_at IS NULL AND dead_lettered_at IS NULL AND attempt_count < 8
                           AND available_at <= CURRENT_TIMESTAMP
                           AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                         ORDER BY available_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE reference_revocation.tasks task
                       SET claim_token = :claim, lease_until = CURRENT_TIMESTAMP + :leaseSeconds * INTERVAL '1 second',
                           attempt_count = task.attempt_count + 1
                      FROM candidate WHERE task.id = candidate.id
                    RETURNING task.*
                    """).param("claim", UUID.randomUUID()).param("leaseSeconds", lease.toSeconds())
                    .query((rs, row) -> new RevocationTask(rs.getObject("id", UUID.class), rs.getString("registration_id"),
                            rs.getString("issuer"), rs.getString("client_id"), rs.getString("revocation_uri"),
                            rs.getString("token_type_hint"), rs.getString("key_id"), rs.getBytes("nonce"),
                            rs.getBytes("ciphertext"), rs.getObject("claim_token", UUID.class), rs.getInt("attempt_count")))
                    .optional();
        });
    }

    boolean complete(RevocationTask task) {
        return Boolean.TRUE.equals(transactions.execute(status -> jdbc.sql("""
                UPDATE reference_revocation.tasks
                   SET completed_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_until = NULL,
                       nonce = NULL, ciphertext = NULL, last_error_code = NULL
                 WHERE id = :id AND claim_token = :claim AND lease_until > CURRENT_TIMESTAMP
                   AND completed_at IS NULL AND dead_lettered_at IS NULL
                """).param("id", task.id()).param("claim", task.claimToken()).update() == 1));
    }

    boolean fail(RevocationTask task, String category, long delaySeconds) {
        return Boolean.TRUE.equals(transactions.execute(status -> jdbc.sql("""
                UPDATE reference_revocation.tasks
                   SET available_at = CURRENT_TIMESTAMP + :delay * INTERVAL '1 second',
                       last_error_code = :category, claim_token = NULL, lease_until = NULL,
                       dead_lettered_at = CASE WHEN attempt_count >= 8 THEN CURRENT_TIMESTAMP ELSE NULL END
                 WHERE id = :id AND claim_token = :claim AND lease_until > CURRENT_TIMESTAMP
                   AND completed_at IS NULL AND dead_lettered_at IS NULL
                """).param("id", task.id()).param("claim", task.claimToken()).param("category", category)
                .param("delay", delaySeconds).update() == 1));
    }
}
