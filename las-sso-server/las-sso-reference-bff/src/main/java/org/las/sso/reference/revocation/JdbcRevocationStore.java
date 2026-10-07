package org.las.sso.reference.revocation;

import java.security.MessageDigest;
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
    private final RevocationTokenCipher cipher;
    private static final long KEY_LOCK = 5_492_486_017_847_824_977L;

    JdbcRevocationStore(
            JdbcClient jdbc, PlatformTransactionManager manager, RevocationTokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(15);
        transactions.executeWithoutResult(
                status -> {
                    lock();
                    if (jdbc.sql("SELECT count(*) FROM reference_revocation.key_state")
                                    .query(Long.class)
                                    .single()
                            == 0) {
                        var outstanding =
                                jdbc.sql(
                                                "SELECT * FROM reference_revocation.tasks WHERE completed_at IS NULL ORDER BY created_at, id")
                                        .query(JdbcRevocationStore::task)
                                        .list();
                        require(
                                outstanding.stream()
                                        .allMatch(task -> cipher.keyId().equals(task.keyId())),
                                "KEY_DRAIN_REQUIRED");
                        outstanding.forEach(cipher::decrypt);
                        jdbc.sql(
                                        "INSERT INTO reference_revocation.key_state (id, key_id, key_verifier) VALUES (1, :key, :proof)")
                                .param("key", cipher.keyId())
                                .param("proof", cipher.verifier())
                                .update();
                    }
                    verifyKey(cipher);
                });
    }

    void insert(RevocationTask task) {
        transactions.executeWithoutResult(
                status -> {
                    guard(cipher);
                    require(cipher.keyId().equals(task.keyId()), "KEY_CONFIGURATION_CHANGED");

                    jdbc.sql(
                                    """
                INSERT INTO reference_revocation.tasks
                    (id, registration_id, issuer, client_id, revocation_uri, token_type_hint, key_id, nonce, ciphertext)
                VALUES (:id, :registration, :issuer, :client, :uri, :hint, :key, :nonce, :ciphertext)
                """)
                            .param("id", task.id())
                            .param("registration", task.registrationId())
                            .param("issuer", task.issuer())
                            .param("client", task.clientId())
                            .param("uri", task.uri())
                            .param("hint", task.hint())
                            .param("key", task.keyId())
                            .param("nonce", task.nonce())
                            .param("ciphertext", task.ciphertext())
                            .update();
                });
    }

    Optional<RevocationTask> claim(Duration lease) {
        return claim(lease, cipher);
    }

    Optional<RevocationTask> claim(Duration lease, RevocationTokenCipher workerKey) {
        return transactions.execute(
                status -> {
                    guard(workerKey);
                    // A crash on the eighth claim must not leave an immortal pending task or a
                    // ninth HTTP attempt.
                    jdbc.sql(
                                    """
                    UPDATE reference_revocation.tasks
                       SET dead_lettered_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_until = NULL,
                           last_error_code = 'LEASE_EXHAUSTED'
                     WHERE completed_at IS NULL AND dead_lettered_at IS NULL AND attempt_count >= 8
                       AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    """)
                            .update();
                    return jdbc.sql(
                                    """
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
                    """)
                            .param("claim", UUID.randomUUID())
                            .param("leaseSeconds", lease.toSeconds())
                            .query(JdbcRevocationStore::task)
                            .optional();
                });
    }

    boolean complete(RevocationTask task) {
        return Boolean.TRUE.equals(
                transactions.execute(
                        status -> {
                            guard(cipher);
                            return jdbc.sql(
                                                    """
                UPDATE reference_revocation.tasks
                   SET completed_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_until = NULL,
                       nonce = NULL, ciphertext = NULL, last_error_code = NULL
                 WHERE id = :id AND claim_token = :claim AND lease_until > CURRENT_TIMESTAMP
                   AND completed_at IS NULL AND dead_lettered_at IS NULL
                """)
                                            .param("id", task.id())
                                            .param("claim", task.claimToken())
                                            .update()
                                    == 1;
                        }));
    }

    boolean fail(RevocationTask task, String category, long delaySeconds) {
        return Boolean.TRUE.equals(
                transactions.execute(
                        status -> {
                            guard(cipher);
                            return jdbc.sql(
                                                    """
                UPDATE reference_revocation.tasks
                   SET available_at = CURRENT_TIMESTAMP + :delay * INTERVAL '1 second',
                       last_error_code = :category, claim_token = NULL, lease_until = NULL,
                       dead_lettered_at = CASE WHEN attempt_count >= 8 THEN CURRENT_TIMESTAMP ELSE NULL END
                 WHERE id = :id AND claim_token = :claim AND lease_until > CURRENT_TIMESTAMP
                   AND completed_at IS NULL AND dead_lettered_at IS NULL
                """)
                                            .param("id", task.id())
                                            .param("claim", task.claimToken())
                                            .param("category", category)
                                            .param("delay", delaySeconds)
                                            .update()
                                    == 1;
                        }));
    }

    void replay(UUID id, int expectedReplay, String actor, String reason) {
        require(
                expectedReplay >= 0 && expectedReplay < Integer.MAX_VALUE,
                "INVALID_REPLAY_GENERATION");
        validateOperator(actor, reason);
        transactions.executeWithoutResult(
                status -> {
                    guard(cipher);
                    var selected =
                            jdbc.sql(
                                            "SELECT * FROM reference_revocation.tasks WHERE id = :id AND dead_lettered_at IS NOT NULL AND completed_at IS NULL AND replay_count = :generation FOR UPDATE")
                                    .param("id", id)
                                    .param("generation", expectedReplay)
                                    .query(JdbcRevocationStore::task)
                                    .optional();
                    require(selected.isPresent(), "DEAD_TASK_OR_GENERATION_MISMATCH");
                    RevocationTask task = selected.orElseThrow();
                    cipher.decrypt(task);
                    int changed =
                            jdbc.sql(
                                            """
                    UPDATE reference_revocation.tasks SET dead_lettered_at = NULL, attempt_count = 0,
                        available_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_until = NULL,
                        last_error_code = NULL, replay_count = replay_count + 1 WHERE id = :id
                    """)
                                    .param("id", id)
                                    .update();
                    audit(
                            "REPLAY",
                            id,
                            actor,
                            reason,
                            expectedReplay + 1,
                            task.attempts(),
                            changed,
                            null,
                            null);
                });
    }

    void rotateKey(RevocationTokenCipher next, String actor, String reason) {
        validateOperator(actor, reason);
        transactions.executeWithoutResult(
                status -> {
                    guard(cipher);
                    require(!cipher.keyId().equals(next.keyId()), "NEW_KEY_ID_REQUIRED");
                    require(!cipher.sameMaterial(next), "NEW_KEY_MATERIAL_REQUIRED");
                    require(
                            jdbc.sql(
                                                    "SELECT count(*) FROM reference_revocation.operator_audit WHERE old_key_id = :key OR new_key_id = :key")
                                            .param("key", next.keyId())
                                            .query(Long.class)
                                            .single()
                                    == 0,
                            "KEY_ID_REUSE_FORBIDDEN");
                    require(
                            jdbc.sql(
                                                    "SELECT count(*) FROM reference_revocation.tasks WHERE completed_at IS NULL")
                                            .query(Long.class)
                                            .single()
                                    == 0,
                            "KEY_DRAIN_REQUIRED");
                    jdbc.sql(
                                    "UPDATE reference_revocation.key_state SET key_id = :key, key_verifier = :proof, changed_at = CURRENT_TIMESTAMP WHERE id = 1")
                            .param("key", next.keyId())
                            .param("proof", next.verifier())
                            .update();
                    audit(
                            "ROTATE_KEY",
                            null,
                            actor,
                            reason,
                            null,
                            null,
                            1,
                            cipher.keyId(),
                            next.keyId());
                });
    }

    int purgeCompleted(Duration retention, int batch, String actor, String reason) {
        validateOperator(actor, reason);
        require(
                retention != null
                        && retention.compareTo(Duration.ofDays(1)) >= 0
                        && retention.compareTo(Duration.ofDays(365)) <= 0
                        && retention.getNano() == 0
                        && batch >= 1
                        && batch <= 1000,
                "INVALID_RETENTION");
        return transactions.execute(
                status -> {
                    guard(cipher);
                    int count =
                            jdbc.sql(
                                            """
                    DELETE FROM reference_revocation.tasks WHERE id IN (
                        SELECT id FROM reference_revocation.tasks
                         WHERE completed_at < CURRENT_TIMESTAMP - :seconds * INTERVAL '1 second'
                         ORDER BY completed_at, id LIMIT :batch FOR UPDATE SKIP LOCKED)
                    """)
                                    .param("seconds", retention.toSeconds())
                                    .param("batch", batch)
                                    .update();
                    if (count > 0)
                        audit(
                                "PURGE_COMPLETED",
                                null,
                                actor,
                                reason,
                                null,
                                null,
                                count,
                                null,
                                null);
                    return count;
                });
    }

    private void lock() {
        jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
        jdbc.sql("SELECT pg_advisory_xact_lock(:lock)")
                .param("lock", KEY_LOCK)
                .query((rs, row) -> 0)
                .single();
    }

    private void guard(RevocationTokenCipher key) {
        lock();
        verifyKey(key);
    }

    private void verifyKey(RevocationTokenCipher key) {
        Boolean matches =
                jdbc.sql(
                                "SELECT key_id, key_verifier FROM reference_revocation.key_state WHERE id = 1")
                        .query(
                                (rs, row) ->
                                        key.keyId().equals(rs.getString(1))
                                                && MessageDigest.isEqual(
                                                        key.verifier(), rs.getBytes(2)))
                        .optional()
                        .orElse(false);
        require(matches, "KEY_CONFIGURATION_CHANGED");
    }

    private void audit(
            String action,
            UUID task,
            String actor,
            String reason,
            Integer generation,
            Integer attempts,
            int affected,
            String oldKey,
            String newKey) {
        jdbc.sql(
                        """
                INSERT INTO reference_revocation.operator_audit
                    (id, action, task_id, actor, reason, replay_count, previous_attempts, affected_count, old_key_id, new_key_id)
                VALUES (:id, :action, :task, :actor, :reason, :generation, :attempts, :affected, :oldKey, :newKey)
                """)
                .param("id", UUID.randomUUID())
                .param("action", action)
                .param("task", task)
                .param("actor", actor)
                .param("reason", reason)
                .param("generation", generation)
                .param("attempts", attempts)
                .param("affected", affected)
                .param("oldKey", oldKey)
                .param("newKey", newKey)
                .update();
    }

    private static void validateOperator(String actor, String reason) {
        require(
                actor != null
                        && actor.matches("[A-Za-z0-9._@-]{1,64}")
                        && reason != null
                        && !reason.isBlank()
                        && reason.length() <= 128
                        && reason.chars().noneMatch(Character::isISOControl),
                "INVALID_OPERATOR_REASON");
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new IllegalStateException(code);
    }

    private static RevocationTask task(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        return new RevocationTask(
                rs.getObject("id", UUID.class),
                rs.getString("registration_id"),
                rs.getString("issuer"),
                rs.getString("client_id"),
                rs.getString("revocation_uri"),
                rs.getString("token_type_hint"),
                rs.getString("key_id"),
                rs.getBytes("nonce"),
                rs.getBytes("ciphertext"),
                rs.getObject("claim_token", UUID.class),
                rs.getInt("attempt_count"));
    }
}
