package org.las.sso.admin;

import java.time.Clock;
import java.time.Duration;
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
public class AdminDeletionRequestService {

    public static final Duration HOLD_MAX = Duration.ofDays(90);
    private static final int MAX_PAGE_SIZE = 200;

    private final JdbcClient jdbcClient;
    private final UuidV7Generator uuidGenerator;
    private final Clock clock;

    @Autowired
    public AdminDeletionRequestService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator) {
        this(jdbcClient, uuidGenerator, Clock.systemUTC());
    }

    AdminDeletionRequestService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.uuidGenerator = uuidGenerator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DeletionPage list(String status, String cursor, int requestedLimit) {
        String normalizedStatus = normalizeStatus(status);
        int limit = Math.max(1, Math.min(requestedLimit, MAX_PAGE_SIZE));
        AdminAuditQueryService.Cursor position = AdminAuditQueryService.decodeCursor(cursor);
        List<DeletionRequest> rows = jdbcClient.sql("""
                SELECT r.id, r.user_id, un.username, r.status::text, r.requested_at,
                       r.execute_after, r.hold_until, r.hold_reason, r.hold_public_summary,
                       u.account_status::text AS account_status
                  FROM account_deletion_requests r
                  JOIN users u ON u.id = r.user_id
                  JOIN usernames un ON un.user_id = r.user_id AND un.valid_to IS NULL
                 WHERE (:status = '' OR r.status::text = :status)
                   AND (CAST(:cursorTime AS timestamptz) IS NULL
                        OR (r.requested_at, r.id) < (
                            CAST(:cursorTime AS timestamptz), CAST(:cursorId AS uuid)))
                 ORDER BY r.requested_at DESC, r.id DESC
                 LIMIT :limit
                """).param("status", normalizedStatus)
                .param("cursorTime", position == null ? null : position.occurredAt().toString())
                .param("cursorId", position == null ? null : position.id().toString())
                .param("limit", limit + 1)
                .query((rs, rowNum) -> new DeletionRequest(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("username"),
                        rs.getString("status"),
                        rs.getObject("requested_at", OffsetDateTime.class),
                        rs.getObject("execute_after", OffsetDateTime.class),
                        rs.getObject("hold_until", OffsetDateTime.class),
                        rs.getString("hold_reason"),
                        rs.getString("hold_public_summary"),
                        rs.getString("account_status")))
                .list();
        boolean hasMore = rows.size() > limit;
        List<DeletionRequest> items = hasMore
                ? List.copyOf(rows.subList(0, limit))
                : List.copyOf(rows);
        String nextCursor = hasMore
                ? AdminAuditQueryService.encodeCursor(
                        items.getLast().requestedAt(), items.getLast().id())
                : null;
        return new DeletionPage(items, nextCursor);
    }

    @Transactional
    public void hold(
            UUID actorUserId,
            String actorSid,
            UUID requestId,
            String reason,
            String publicSummary,
            OffsetDateTime holdUntil,
            UUID requestRequestId) {
        if (holdUntil == null || holdUntil.isBefore(now())) {
            throw new AdminUserManagementException("INVALID_HOLD_UNTIL");
        }
        if (holdUntil.isAfter(now().plus(HOLD_MAX))) {
            throw new AdminUserManagementException("INVALID_HOLD_UNTIL");
        }
        int updated = jdbcClient.sql("""
                UPDATE account_deletion_requests
                   SET status = 'HELD', hold_until = :holdUntil,
                       held_by_user_id = :actorUserId, hold_reason = :reason,
                       hold_public_summary = :publicSummary
                 WHERE id = :requestId
                   AND status = 'PENDING'
                """).param("holdUntil", holdUntil)
                .param("actorUserId", actorUserId)
                .param("reason", reason)
                .param("publicSummary", publicSummary)
                .param("requestId", requestId).update();
        if (updated == 0) {
            throw new AdminUserManagementException("ACCOUNT_STATE_CONFLICT");
        }
        UUID userId = userIdOf(requestId);
        securityEvent(userId, "DELETION_HELD_BY_ADMIN", "HIGH", requestRequestId, now());
        insertAudit(actorUserId, actorSid, userId, "ADMIN_DELETION_HOLD",
                reason, requestRequestId, now());
    }

    @Transactional
    public void release(
            UUID actorUserId,
            String actorSid,
            UUID requestId,
            String reason,
            UUID requestRequestId) {
        int updated = jdbcClient.sql("""
                UPDATE account_deletion_requests
                   SET status = 'PENDING', hold_until = NULL,
                       held_by_user_id = NULL, hold_reason = NULL,
                       hold_public_summary = NULL
                 WHERE id = :requestId
                   AND status = 'HELD'
                """).param("requestId", requestId).update();
        if (updated == 0) {
            throw new AdminUserManagementException("ACCOUNT_STATE_CONFLICT");
        }
        UUID userId = userIdOf(requestId);
        securityEvent(userId, "DELETION_RELEASED_BY_ADMIN", "INFO", requestRequestId, now());
        insertAudit(actorUserId, actorSid, userId, "ADMIN_DELETION_RELEASE",
                reason, requestRequestId, now());
    }

    private UUID userIdOf(UUID requestId) {
        return jdbcClient.sql("""
                SELECT user_id FROM account_deletion_requests WHERE id = :requestId
                """).param("requestId", requestId)
                .query(UUID.class).optional()
                .orElseThrow(() -> new AdminUserManagementException("ADMIN_TARGET_NOT_FOUND"));
    }

    private void insertAudit(
            UUID actorUserId,
            String actorSid,
            UUID targetUserId,
            String action,
            String reason,
            UUID requestId,
            OffsetDateTime now) {
        UUID actorSessionId = actorSid == null ? null : jdbcClient.sql("""
                SELECT id FROM user_sessions WHERE sid = :sid AND user_id = :actorUserId
                """).param("sid", actorSid).param("actorUserId", actorUserId)
                .query(UUID.class).optional().orElse(null);
        jdbcClient.sql("""
                INSERT INTO admin_audit_events
                    (id, occurred_at, actor_user_id, actor_session_id, action,
                     target_type, target_id, request_id, result, reason)
                VALUES (:id, :now, :actorUserId, :actorSessionId, :action,
                        'USER', :targetId, :requestId, 'SUCCESS', :reason)
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("actorUserId", actorUserId).param("actorSessionId", actorSessionId)
                .param("action", action).param("targetId", targetUserId.toString())
                .param("requestId", requestId).param("reason", reason).update();
    }

    private void securityEvent(
            UUID userId, String type, String severity, UUID requestId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                VALUES (:id, :now, :userId, :type, :severity, :requestId, '{}'::jsonb)
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("userId", userId).param("type", type).param("severity", severity)
                .param("requestId", requestId).update();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        String normalized = status.strip().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("PENDING", "HELD", "CANCELLED", "EXECUTED").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported deletion status");
        }
        return normalized;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record DeletionRequest(
            UUID id,
            UUID userId,
            String username,
            String status,
            OffsetDateTime requestedAt,
            OffsetDateTime executeAfter,
            OffsetDateTime holdUntil,
            String holdReason,
            String holdPublicSummary,
            String accountStatus) {
    }

    public record DeletionPage(List<DeletionRequest> items, String nextCursor) {
        public DeletionPage {
            items = List.copyOf(items);
        }
    }
}
