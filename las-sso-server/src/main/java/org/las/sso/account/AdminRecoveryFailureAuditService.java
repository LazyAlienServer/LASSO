package org.las.sso.account;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.las.sso.identity.UuidV7Generator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminRecoveryFailureAuditService {

    private final JdbcClient jdbcClient;
    private final UuidV7Generator uuidGenerator;

    public AdminRecoveryFailureAuditService(JdbcClient jdbcClient, UuidV7Generator uuidGenerator) {
        this.jdbcClient = jdbcClient;
        this.uuidGenerator = uuidGenerator;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejectedAttempt(
            String username, byte[] credentialHash, UUID requestId, OffsetDateTime now) {
        Match match =
                jdbcClient
                        .sql(
                                """
                SELECT arc.id, arc.target_user_id, arc.issued_by_user_id,
                       arc.expires_at, arc.used_at, arc.revoked_at
                  FROM admin_recovery_credentials arc
                  JOIN usernames un ON un.user_id = arc.target_user_id
                 WHERE un.username = :username COLLATE "C"
                   AND un.valid_to IS NULL
                   AND arc.credential_hash = :credentialHash
                 ORDER BY arc.created_at DESC
                 LIMIT 1
                """)
                        .param("username", username)
                        .param("credentialHash", credentialHash)
                        .query(
                                (rs, rowNum) ->
                                        new Match(
                                                rs.getObject("id", UUID.class),
                                                rs.getObject("target_user_id", UUID.class),
                                                rs.getObject("issued_by_user_id", UUID.class),
                                                rs.getObject("expires_at", OffsetDateTime.class),
                                                rs.getObject("used_at", OffsetDateTime.class),
                                                rs.getObject("revoked_at", OffsetDateTime.class)))
                        .optional()
                        .orElse(null);
        if (match == null) return;
        String result =
                !match.expiresAt().isAfter(now)
                        ? "EXPIRED"
                        : match.revokedAt() != null
                                ? "REVOKED"
                                : match.usedAt() != null ? "ALREADY_USED" : "FAILURE";
        jdbcClient
                .sql(
                        """
                INSERT INTO admin_audit_events
                    (id, occurred_at, actor_user_id, action, target_type, target_id,
                     request_id, result, details_json)
                VALUES (:id, :now, :actorUserId, 'ADMIN_RECOVERY_CREDENTIAL_REJECTED',
                        'USER', :targetId, :requestId, :result,
                        jsonb_build_object('credentialId', :credentialId))
                """)
                .param("id", uuidGenerator.generate())
                .param("now", now)
                .param("actorUserId", match.issuedByUserId())
                .param("targetId", match.targetUserId().toString())
                .param("requestId", requestId)
                .param("result", result)
                .param("credentialId", match.id().toString())
                .update();
    }

    private record Match(
            UUID id,
            UUID targetUserId,
            UUID issuedByUserId,
            OffsetDateTime expiresAt,
            OffsetDateTime usedAt,
            OffsetDateTime revokedAt) {}
}
