package org.las.sso.maintenance;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.las.sso.identity.UuidV7Generator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminRecoveryExpiryJob {

    private static final int BATCH_SIZE = 100;

    private final JdbcClient jdbcClient;
    private final UuidV7Generator uuidGenerator;

    public AdminRecoveryExpiryJob(JdbcClient jdbcClient, UuidV7Generator uuidGenerator) {
        this.jdbcClient = jdbcClient;
        this.uuidGenerator = uuidGenerator;
    }

    @Scheduled(initialDelay = 300_000, fixedDelay = 300_000)
    @Transactional
    public int expire() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<ExpiredCredential> expired = jdbcClient.sql("""
                WITH selected AS (
                    SELECT id
                      FROM admin_recovery_credentials
                     WHERE used_at IS NULL AND revoked_at IS NULL AND expires_at <= :now
                     ORDER BY expires_at, id
                     LIMIT :limit
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE admin_recovery_credentials credential
                   SET revoked_at = :now
                  FROM selected
                 WHERE credential.id = selected.id
                RETURNING credential.id, credential.target_user_id,
                          credential.issued_by_user_id
                """).param("now", now).param("limit", BATCH_SIZE)
                .query((rs, rowNum) -> new ExpiredCredential(
                        rs.getObject("id", UUID.class),
                        rs.getObject("target_user_id", UUID.class),
                        rs.getObject("issued_by_user_id", UUID.class)))
                .list();
        for (ExpiredCredential credential : expired) {
            UUID requestId = uuidGenerator.generate();
            jdbcClient.sql("""
                    INSERT INTO admin_audit_events
                        (id, occurred_at, actor_user_id, action, target_type, target_id,
                         request_id, result, details_json)
                    VALUES (:id, :now, :actorUserId, 'ADMIN_RECOVERY_CREDENTIAL_EXPIRED',
                            'USER', :targetId, :requestId, 'EXPIRED',
                            jsonb_build_object('credentialId', :credentialId))
                    """).param("id", uuidGenerator.generate()).param("now", now)
                    .param("actorUserId", credential.issuedByUserId())
                    .param("targetId", credential.targetUserId().toString())
                    .param("requestId", requestId)
                    .param("credentialId", credential.id().toString()).update();
            jdbcClient.sql("""
                    INSERT INTO security_events
                        (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                    VALUES (:id, :now, :userId, 'ADMIN_RECOVERY_CREDENTIAL_EXPIRED',
                            'INFO', :requestId,
                            jsonb_build_object('credentialId', :credentialId))
                    """).param("id", uuidGenerator.generate()).param("now", now)
                    .param("userId", credential.targetUserId()).param("requestId", requestId)
                    .param("credentialId", credential.id().toString()).update();
        }
        return expired.size();
    }

    private record ExpiredCredential(UUID id, UUID targetUserId, UUID issuedByUserId) {
    }
}
