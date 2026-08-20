package org.las.sso.account;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.las.sso.security.TransientSecurityKeyHasher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationIdempotencyService {

    private final JdbcClient jdbcClient;
    private final TransientSecurityKeyHasher keyHasher;

    public OperationIdempotencyService(
            JdbcClient jdbcClient,
            TransientSecurityKeyHasher keyHasher) {
        this.jdbcClient = jdbcClient;
        this.keyHasher = keyHasher;
    }

    @Transactional
    public <T> T execute(
            UUID actorUserId,
            String operation,
            String idempotencyKey,
            String requestMaterial,
            Supplier<T> action,
            Function<T, StoredResult> resultMapper,
            Function<StoredResult, T> replayMapper) {
        validateKey(idempotencyKey);
        byte[] requestHash = keyHasher.hash(
                "operation-idempotency-request", operation + "\u0000" + requestMaterial);
        long lockId = ByteBuffer.wrap(keyHasher.hash(
                "operation-idempotency-lock",
                actorUserId + "\u0000" + operation + "\u0000" + idempotencyKey)).getLong();
        jdbcClient.sql("SELECT true FROM pg_advisory_xact_lock(:lockId)")
                .param("lockId", lockId).query(Boolean.class).single();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                DELETE FROM operation_idempotency
                 WHERE actor_user_id = :actorUserId
                   AND operation = :operation
                   AND idempotency_key = :idempotencyKey
                   AND expires_at <= :now
                """).param("actorUserId", actorUserId).param("operation", operation)
                .param("idempotencyKey", idempotencyKey).param("now", now).update();
        Existing existing = jdbcClient.sql("""
                SELECT request_hash, result_ref, result_time
                  FROM operation_idempotency
                 WHERE actor_user_id = :actorUserId
                   AND operation = :operation
                   AND idempotency_key = :idempotencyKey
                """).param("actorUserId", actorUserId).param("operation", operation)
                .param("idempotencyKey", idempotencyKey)
                .query((rs, rowNum) -> new Existing(
                        rs.getBytes("request_hash"),
                        rs.getString("result_ref"),
                        rs.getObject("result_time", OffsetDateTime.class)))
                .optional().orElse(null);
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.requestHash(), requestHash)) {
                throw new IdempotencyConflictException();
            }
            return replayMapper.apply(new StoredResult(existing.resultRef(), existing.resultTime()));
        }

        T result = action.get();
        StoredResult stored = resultMapper.apply(result);
        jdbcClient.sql("""
                INSERT INTO operation_idempotency
                    (actor_user_id, operation, idempotency_key, request_hash,
                     result_ref, result_time, created_at, expires_at)
                VALUES (:actorUserId, :operation, :idempotencyKey, :requestHash,
                        :resultRef, :resultTime, :now, :expiresAt)
                """).param("actorUserId", actorUserId).param("operation", operation)
                .param("idempotencyKey", idempotencyKey).param("requestHash", requestHash)
                .param("resultRef", stored.resultRef()).param("resultTime", stored.resultTime())
                .param("now", now).param("expiresAt", now.plusHours(24)).update();
        return result;
    }

    @Transactional
    public <T> T executeWithoutResult(
            UUID actorUserId,
            String operation,
            String idempotencyKey,
            String requestMaterial,
            Supplier<T> action,
            Supplier<T> replay) {
        return execute(actorUserId, operation, idempotencyKey, requestMaterial, action,
                ignored -> StoredResult.empty(), ignored -> replay.get());
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
    }

    public record StoredResult(String resultRef, OffsetDateTime resultTime) {
        public static StoredResult empty() {
            return new StoredResult(null, null);
        }
    }

    private record Existing(byte[] requestHash, String resultRef, OffsetDateTime resultTime) {
    }
}
