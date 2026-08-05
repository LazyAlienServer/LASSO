package org.las.sso.account;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.las.sso.identity.UuidV7Generator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryCodeRotationService {

    private final JdbcClient jdbcClient;
    private final RecoveryCodeService recoveryCodes;
    private final UuidV7Generator uuidGenerator;
    private final Clock clock;

    @Autowired
    public RecoveryCodeRotationService(
            JdbcClient jdbcClient,
            RecoveryCodeService recoveryCodes,
            UuidV7Generator uuidGenerator) {
        this(jdbcClient, recoveryCodes, uuidGenerator, Clock.systemUTC());
    }

    RecoveryCodeRotationService(
            JdbcClient jdbcClient,
            RecoveryCodeService recoveryCodes,
            UuidV7Generator uuidGenerator,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.recoveryCodes = recoveryCodes;
        this.uuidGenerator = uuidGenerator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Status status(UUID userId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FILTER (WHERE used_at IS NULL AND revoked_at IS NULL) AS available,
                       MAX(created_at) AS generated_at
                  FROM recovery_codes
                 WHERE user_id = :userId
                """).param("userId", userId)
                .query((rs, rowNum) -> new Status(
                        rs.getInt("available"),
                        rs.getObject("generated_at", OffsetDateTime.class)))
                .single();
    }

    @Transactional
    public RotationResult rotate(UUID userId) {
        jdbcClient.sql("SELECT id FROM users WHERE id = :userId AND account_status = 'ACTIVE' FOR UPDATE")
                .param("userId", userId).query(UUID.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("USER_NOT_ACTIVE"));
        int generation = jdbcClient.sql("""
                SELECT COALESCE(MAX(generation), 0) + 1
                  FROM recovery_codes
                 WHERE user_id = :userId
                """).param("userId", userId).query(Integer.class).single();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<RecoveryCodeService.RecoveryCode> batch = recoveryCodes.generateBatch();

        jdbcClient.sql("""
                UPDATE recovery_codes
                   SET revoked_at = :now
                 WHERE user_id = :userId AND revoked_at IS NULL
                """).param("now", now).param("userId", userId).update();
        for (RecoveryCodeService.RecoveryCode code : batch) {
            jdbcClient.sql("""
                    INSERT INTO recovery_codes
                        (id, user_id, code_hash, generation, created_at)
                    VALUES (:id, :userId, :codeHash, :generation, :now)
                    """).param("id", uuidGenerator.generate()).param("userId", userId)
                    .param("codeHash", code.digest()).param("generation", generation)
                    .param("now", now).update();
        }
        jdbcClient.sql("""
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                VALUES (:id, :now, :userId, 'RECOVERY_CODES_ROTATED', 'INFO', :requestId,
                        jsonb_build_object('generation', :generation))
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("userId", userId).param("requestId", uuidGenerator.generate())
                .param("generation", generation).update();
        return new RotationResult(
                generation,
                now,
                batch.stream().map(RecoveryCodeService.RecoveryCode::plaintext).toList());
    }

    public record Status(int availableCount, OffsetDateTime generatedAt) {
    }

    public record RotationResult(
            int generation,
            OffsetDateTime generatedAt,
            List<String> recoveryCodes) {
        public RotationResult {
            recoveryCodes = List.copyOf(recoveryCodes);
        }
    }
}
