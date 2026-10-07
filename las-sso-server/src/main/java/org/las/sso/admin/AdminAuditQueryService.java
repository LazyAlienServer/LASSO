package org.las.sso.admin;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditQueryService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final Base64.Encoder CURSOR_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder CURSOR_DECODER = Base64.getUrlDecoder();

    private final JdbcClient jdbcClient;

    public AdminAuditQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public AuditPage auditEvents(
            String action,
            String targetId,
            String from,
            String to,
            String cursor,
            int requestedLimit) {
        int limit = clampLimit(requestedLimit);
        Cursor position = decodeCursor(cursor);
        OffsetDateTime fromTime = parseTime(from, "Invalid audit time range");
        OffsetDateTime toTime = parseTime(to, "Invalid audit time range");
        List<AuditEvent> rows =
                jdbcClient
                        .sql(
                                """
                SELECT a.id, a.occurred_at, a.actor_user_id, a.action, a.target_type,
                       a.target_id, a.request_id, a.source_ip_prefix_hash, a.result,
                       a.reason,
                       CASE WHEN a.action IN ('ADMIN_USER_FREEZE', 'ADMIN_USER_UNFREEZE',
                                              'ADMIN_USER_REVOKE_SESSIONS')
                            THEN a.details_json->>'previousStatus' END AS previous_status,
                       CASE WHEN a.action IN ('ADMIN_USER_FREEZE', 'ADMIN_USER_UNFREEZE',
                                              'ADMIN_USER_REVOKE_SESSIONS')
                            THEN a.details_json->>'nextStatus' END AS next_status,
                       CASE WHEN a.action = 'ADMIN_RECOVERY_CREDENTIAL_ISSUE'
                            THEN a.details_json->>'expiresAt' END AS issued_expires_at,
                       CASE WHEN a.action = 'ADMIN_RECOVERY_CREDENTIAL_ISSUE'
                            THEN a.details_json->>'previousStatus' END AS issued_previous_status,
                       (SELECT un.username FROM usernames un
                         WHERE un.user_id = a.actor_user_id AND un.valid_to IS NULL)
                         AS actor_username
                  FROM admin_audit_events a
                 WHERE (:action = '' OR a.action = :action)
                   AND (:targetId = '' OR a.target_id = :targetId)
                   AND (CAST(:fromTime AS timestamptz) IS NULL
                        OR a.occurred_at >= CAST(:fromTime AS timestamptz))
                   AND (CAST(:toTime AS timestamptz) IS NULL
                        OR a.occurred_at < CAST(:toTime AS timestamptz))
                   AND (CAST(:cursorTime AS timestamptz) IS NULL
                        OR (a.occurred_at, a.id) < (
                            CAST(:cursorTime AS timestamptz), CAST(:cursorId AS uuid)))
                 ORDER BY a.occurred_at DESC, a.id DESC
                 LIMIT :limit
                """)
                        .param("action", action == null ? "" : action)
                        .param("targetId", targetId == null ? "" : targetId)
                        .param("fromTime", fromTime)
                        .param("toTime", toTime)
                        .param(
                                "cursorTime",
                                position == null ? null : position.occurredAt().toString())
                        .param("cursorId", position == null ? null : position.id().toString())
                        .param("limit", limit + 1)
                        .query(
                                (rs, rowNum) ->
                                        new AuditEvent(
                                                rs.getObject("id", UUID.class),
                                                rs.getObject("occurred_at", OffsetDateTime.class),
                                                rs.getObject("actor_user_id", UUID.class),
                                                rs.getString("actor_username"),
                                                rs.getString("action"),
                                                rs.getString("target_type"),
                                                rs.getString("target_id"),
                                                rs.getObject("request_id", UUID.class),
                                                maskIp(rs.getBytes("source_ip_prefix_hash")),
                                                rs.getString("result"),
                                                rs.getString("reason"),
                                                whitelistedDetails(
                                                        rs.getString("previous_status"),
                                                        rs.getString("next_status"),
                                                        rs.getString("issued_expires_at"),
                                                        rs.getString("issued_previous_status"))))
                        .list();
        return auditPage(rows, limit);
    }

    @Transactional(readOnly = true)
    public SecurityEventPage securityEvents(
            String type,
            String severity,
            String userId,
            String from,
            String to,
            String cursor,
            int requestedLimit) {
        int limit = clampLimit(requestedLimit);
        Cursor position = decodeCursor(cursor);
        OffsetDateTime fromTime = parseTime(from, "Invalid security event time range");
        OffsetDateTime toTime = parseTime(to, "Invalid security event time range");
        UUID userIdUuid = parseUserId(userId);
        List<SecurityEvent> rows =
                jdbcClient
                        .sql(
                                """
                SELECT s.id, s.occurred_at, s.user_id, s.event_type, s.severity,
                       s.request_id, s.source_ip_prefix_hash, s.client_id,
                       (SELECT un.username FROM usernames un
                         WHERE un.user_id = s.user_id AND un.valid_to IS NULL)
                         AS username
                  FROM security_events s
                 WHERE (:type = '' OR s.event_type = :type)
                   AND (:severity = '' OR s.severity = :severity)
                   AND (CAST(:userId AS uuid) IS NULL OR s.user_id = CAST(:userId AS uuid))
                   AND (CAST(:fromTime AS timestamptz) IS NULL
                        OR s.occurred_at >= CAST(:fromTime AS timestamptz))
                   AND (CAST(:toTime AS timestamptz) IS NULL
                        OR s.occurred_at < CAST(:toTime AS timestamptz))
                   AND (CAST(:cursorTime AS timestamptz) IS NULL
                        OR (s.occurred_at, s.id) < (
                            CAST(:cursorTime AS timestamptz), CAST(:cursorId AS uuid)))
                 ORDER BY s.occurred_at DESC, s.id DESC
                 LIMIT :limit
                """)
                        .param("type", type == null ? "" : type)
                        .param("severity", severity == null ? "" : severity)
                        .param("userId", userIdUuid)
                        .param("fromTime", fromTime)
                        .param("toTime", toTime)
                        .param(
                                "cursorTime",
                                position == null ? null : position.occurredAt().toString())
                        .param("cursorId", position == null ? null : position.id().toString())
                        .param("limit", limit + 1)
                        .query(
                                (rs, rowNum) ->
                                        new SecurityEvent(
                                                rs.getObject("id", UUID.class),
                                                rs.getObject("occurred_at", OffsetDateTime.class),
                                                rs.getObject("user_id", UUID.class),
                                                rs.getString("username"),
                                                rs.getString("event_type"),
                                                rs.getString("severity"),
                                                rs.getObject("request_id", UUID.class),
                                                maskIp(rs.getBytes("source_ip_prefix_hash")),
                                                rs.getString("client_id")))
                        .list();
        return securityPage(rows, limit);
    }

    private static AuditPage auditPage(List<AuditEvent> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        List<AuditEvent> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String nextCursor =
                hasMore ? encodeCursor(items.getLast().occurredAt(), items.getLast().id()) : null;
        return new AuditPage(items, nextCursor);
    }

    private static SecurityEventPage securityPage(List<SecurityEvent> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        List<SecurityEvent> items =
                hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String nextCursor =
                hasMore ? encodeCursor(items.getLast().occurredAt(), items.getLast().id()) : null;
        return new SecurityEventPage(items, nextCursor);
    }

    private static Map<String, String> whitelistedDetails(
            String previousStatus,
            String nextStatus,
            String issuedExpiresAt,
            String issuedPreviousStatus) {
        Map<String, String> visible = new LinkedHashMap<>();
        if (previousStatus != null) {
            visible.put("previousStatus", previousStatus);
        }
        if (nextStatus != null) {
            visible.put("nextStatus", nextStatus);
        }
        if (issuedExpiresAt != null) {
            visible.put("expiresAt", issuedExpiresAt);
        }
        if (issuedPreviousStatus != null) {
            visible.put("previousStatus", issuedPreviousStatus);
        }
        return visible;
    }

    private static String maskIp(byte[] prefixHash) {
        if (prefixHash == null || prefixHash.length == 0) {
            return null;
        }
        int length = Math.min(4, prefixHash.length);
        return java.util.HexFormat.of().formatHex(prefixHash, 0, length);
    }

    static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(CURSOR_DECODER.decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid audit cursor");
        }
        int separator = decoded.indexOf('|');
        if (separator <= 0 || separator == decoded.length() - 1) {
            throw new IllegalArgumentException("Invalid audit cursor");
        }
        try {
            return new Cursor(
                    OffsetDateTime.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid audit cursor");
        }
    }

    static String encodeCursor(OffsetDateTime occurredAt, UUID id) {
        return CURSOR_ENCODER.encodeToString(
                (occurredAt + "|" + id).getBytes(StandardCharsets.US_ASCII));
    }

    private static OffsetDateTime parseTime(String value, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(message);
        }
    }

    private static UUID parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid security event user id");
        }
    }

    private static int clampLimit(int requestedLimit) {
        return Math.max(1, Math.min(requestedLimit, MAX_PAGE_SIZE));
    }

    public record AuditEvent(
            UUID id,
            OffsetDateTime occurredAt,
            UUID actorUserId,
            String actorUsername,
            String action,
            String targetType,
            String targetId,
            UUID requestId,
            String ipPrefix,
            String result,
            String reason,
            Map<String, String> details) {}

    public record AuditPage(List<AuditEvent> items, String nextCursor) {
        public AuditPage {
            items = List.copyOf(items);
        }
    }

    public record SecurityEvent(
            UUID id,
            OffsetDateTime occurredAt,
            UUID userId,
            String username,
            String eventType,
            String severity,
            UUID requestId,
            String ipPrefix,
            String clientId) {}

    public record SecurityEventPage(List<SecurityEvent> items, String nextCursor) {
        public SecurityEventPage {
            items = List.copyOf(items);
        }
    }

    record Cursor(OffsetDateTime occurredAt, UUID id) {}
}
