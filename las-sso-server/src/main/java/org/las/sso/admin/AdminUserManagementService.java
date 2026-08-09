package org.las.sso.admin;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.las.sso.identity.UuidV7Generator;
import org.las.sso.session.UserSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserManagementService {

    private static final int MAX_PAGE_SIZE = 200;

    private final JdbcClient jdbcClient;
    private final UserSessionService userSessions;
    private final UuidV7Generator uuidGenerator;
    private final Clock clock;

    @Autowired
    public AdminUserManagementService(
            JdbcClient jdbcClient,
            UserSessionService userSessions,
            UuidV7Generator uuidGenerator) {
        this(jdbcClient, userSessions, uuidGenerator, Clock.systemUTC());
    }

    AdminUserManagementService(
            JdbcClient jdbcClient,
            UserSessionService userSessions,
            UuidV7Generator uuidGenerator,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.userSessions = userSessions;
        this.uuidGenerator = uuidGenerator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserPage users(String query, String status, UUID cursor, int requestedLimit) {
        String normalizedQuery = query == null ? "" : query.strip();
        String normalizedStatus = normalizeStatus(status);
        int limit = Math.max(1, Math.min(requestedLimit, MAX_PAGE_SIZE));
        List<UserSummary> rows = jdbcClient.sql("""
                SELECT u.id, un.username, u.display_name, u.account_status::text,
                       u.is_sso_admin, u.created_at,
                       (SELECT COUNT(*) FROM user_sessions s
                         WHERE s.user_id = u.id AND s.status = 'ACTIVE'
                           AND s.idle_expires_at > CURRENT_TIMESTAMP
                           AND s.absolute_expires_at > CURRENT_TIMESTAMP) AS active_sessions
                  FROM users u
                  JOIN usernames un ON un.user_id = u.id AND un.valid_to IS NULL
                 WHERE (:query = '' OR un.username ILIKE '%' || :query || '%'
                        OR u.display_name ILIKE '%' || :query || '%'
                        OR u.id::text = :query)
                   AND (:status = '' OR u.account_status::text = :status)
                   AND (CAST(:cursor AS uuid) IS NULL OR (u.created_at, u.id) < (
                        SELECT cursor_user.created_at, cursor_user.id
                          FROM users cursor_user
                         WHERE cursor_user.id = CAST(:cursor AS uuid)))
                 ORDER BY u.created_at DESC, u.id DESC
                 LIMIT :limit
                """).param("query", normalizedQuery)
                .param("status", normalizedStatus)
                .param("cursor", cursor == null ? null : cursor.toString())
                .param("limit", limit + 1)
                .query((rs, rowNum) -> new UserSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("account_status"),
                        rs.getBoolean("is_sso_admin"),
                        rs.getLong("active_sessions"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        boolean hasMore = rows.size() > limit;
        List<UserSummary> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String nextCursor = hasMore ? items.getLast().userId().toString() : null;
        String previousCursor = previousCursor(
                normalizedQuery, normalizedStatus, cursor, limit);
        return new UserPage(items, nextCursor, previousCursor);
    }

    private String previousCursor(
            String query, String status, UUID cursor, int limit) {
        if (cursor == null) return null;
        return jdbcClient.sql("""
                SELECT u.id
                  FROM users u
                  JOIN usernames un ON un.user_id = u.id AND un.valid_to IS NULL
                 WHERE (:query = '' OR un.username ILIKE '%' || :query || '%'
                        OR u.display_name ILIKE '%' || :query || '%'
                        OR u.id::text = :query)
                   AND (:status = '' OR u.account_status::text = :status)
                   AND (u.created_at, u.id) > (
                        SELECT cursor_user.created_at, cursor_user.id
                          FROM users cursor_user
                         WHERE cursor_user.id = CAST(:cursor AS uuid))
                 ORDER BY u.created_at ASC, u.id ASC
                 OFFSET :offset
                 LIMIT 1
                """).param("query", query)
                .param("status", status)
                .param("cursor", cursor.toString())
                .param("offset", limit - 1)
                .query(UUID.class)
                .optional()
                .map(UUID::toString)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public UserDetails user(UUID userId) {
        return jdbcClient.sql("""
                SELECT u.id, un.username, u.display_name, u.account_status::text,
                       u.is_sso_admin, u.created_at, u.updated_at, u.frozen_at,
                       pc.state::text AS credential_state,
                       pc.password_changed_at, pc.locked_until,
                       (SELECT COUNT(*) FROM user_sessions s
                         WHERE s.user_id = u.id AND s.status = 'ACTIVE'
                           AND s.idle_expires_at > CURRENT_TIMESTAMP
                           AND s.absolute_expires_at > CURRENT_TIMESTAMP) AS active_sessions,
                       (SELECT COUNT(*) FROM oauth2_authorization a
                         WHERE a.principal_name = u.id::text) AS active_authorizations
                  FROM users u
                  JOIN usernames un ON un.user_id = u.id AND un.valid_to IS NULL
                  JOIN password_credentials pc ON pc.user_id = u.id
                 WHERE u.id = :userId
                """).param("userId", userId)
                .query((rs, rowNum) -> new UserDetails(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("account_status"),
                        rs.getBoolean("is_sso_admin"),
                        rs.getString("credential_state"),
                        rs.getLong("active_sessions"),
                        rs.getLong("active_authorizations"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getObject("frozen_at", OffsetDateTime.class),
                        rs.getObject("password_changed_at", OffsetDateTime.class),
                        rs.getObject("locked_until", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new AdminUserManagementException("ADMIN_TARGET_NOT_FOUND"));
    }

    @Transactional
    public void freeze(
            UUID actorUserId,
            String actorSid,
            UUID targetUserId,
            String reason,
            UUID requestId) {
        LockedTarget target = lockTarget(actorUserId, targetUserId);
        if (!"ACTIVE".equals(target.status())) {
            throw new AdminUserManagementException("ACCOUNT_STATE_CONFLICT");
        }
        OffsetDateTime now = now();
        jdbcClient.sql("""
                UPDATE users
                   SET account_status = 'FROZEN', frozen_at = :now, updated_at = :now
                 WHERE id = :targetUserId
                """).param("now", now).param("targetUserId", targetUserId).update();
        userSessions.logoutAll(targetUserId, "ADMIN_FROZEN");
        insertSecurityEvent(targetUserId, "ACCOUNT_FROZEN_BY_ADMIN", "HIGH", requestId, now);
        insertAudit(actorUserId, actorSid, targetUserId, "ADMIN_USER_FREEZE", reason,
                target.status(), "FROZEN", requestId, now);
    }

    @Transactional
    public void unfreeze(
            UUID actorUserId,
            String actorSid,
            UUID targetUserId,
            String reason,
            UUID requestId) {
        LockedTarget target = lockTarget(actorUserId, targetUserId);
        if (!"FROZEN".equals(target.status())) {
            throw new AdminUserManagementException("ACCOUNT_STATE_CONFLICT");
        }
        OffsetDateTime now = now();
        jdbcClient.sql("""
                UPDATE users
                   SET account_status = 'ACTIVE', frozen_at = NULL, updated_at = :now
                 WHERE id = :targetUserId
                """).param("now", now).param("targetUserId", targetUserId).update();
        insertSecurityEvent(targetUserId, "ACCOUNT_UNFROZEN_BY_ADMIN", "INFO", requestId, now);
        insertAudit(actorUserId, actorSid, targetUserId, "ADMIN_USER_UNFREEZE", reason,
                target.status(), "ACTIVE", requestId, now);
    }

    @Transactional
    public void revokeSessions(
            UUID actorUserId,
            String actorSid,
            UUID targetUserId,
            String reason,
            UUID requestId) {
        LockedTarget target = lockTarget(actorUserId, targetUserId);
        OffsetDateTime now = now();
        userSessions.logoutAll(targetUserId, "ADMIN_REVOKED_SESSIONS");
        insertSecurityEvent(targetUserId, "SESSIONS_REVOKED_BY_ADMIN", "HIGH", requestId, now);
        insertAudit(actorUserId, actorSid, targetUserId, "ADMIN_USER_REVOKE_SESSIONS", reason,
                target.status(), target.status(), requestId, now);
    }

    private LockedTarget lockTarget(UUID actorUserId, UUID targetUserId) {
        if (actorUserId.equals(targetUserId)) {
            throw new AdminUserManagementException("ADMIN_SELF_ACTION_FORBIDDEN");
        }
        LockedTarget target = jdbcClient.sql("""
                SELECT account_status::text, is_sso_admin
                  FROM users
                 WHERE id = :targetUserId
                 FOR UPDATE
                """).param("targetUserId", targetUserId)
                .query((rs, rowNum) -> new LockedTarget(
                        rs.getString("account_status"), rs.getBoolean("is_sso_admin")))
                .optional()
                .orElseThrow(() -> new AdminUserManagementException("ADMIN_TARGET_NOT_FOUND"));
        if (target.administrator()) {
            throw new AdminUserManagementException("ADMIN_TARGET_IS_ADMIN");
        }
        if ("DELETED".equals(target.status())) {
            throw new AdminUserManagementException("ACCOUNT_STATE_CONFLICT");
        }
        return target;
    }

    private void insertAudit(
            UUID actorUserId,
            String actorSid,
            UUID targetUserId,
            String action,
            String reason,
            String previousStatus,
            String nextStatus,
            UUID requestId,
            OffsetDateTime now) {
        UUID actorSessionId = actorSid == null ? null : jdbcClient.sql("""
                SELECT id FROM user_sessions WHERE sid = :sid AND user_id = :actorUserId
                """).param("sid", actorSid).param("actorUserId", actorUserId)
                .query(UUID.class).optional().orElse(null);
        jdbcClient.sql("""
                INSERT INTO admin_audit_events
                    (id, occurred_at, actor_user_id, actor_session_id, action,
                     target_type, target_id, request_id, result, reason, details_json)
                VALUES (:id, :now, :actorUserId, :actorSessionId, :action,
                        'USER', :targetId, :requestId, 'SUCCESS', :reason,
                        jsonb_build_object('previousStatus', :previousStatus,
                                           'nextStatus', :nextStatus))
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("actorUserId", actorUserId).param("actorSessionId", actorSessionId)
                .param("action", action).param("targetId", targetUserId.toString())
                .param("requestId", requestId).param("reason", reason)
                .param("previousStatus", previousStatus).param("nextStatus", nextStatus).update();
    }

    private void insertSecurityEvent(
            UUID userId,
            String type,
            String severity,
            UUID requestId,
            OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                VALUES (:id, :now, :userId, :type, :severity, :requestId, '{}'::jsonb)
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("userId", userId).param("type", type).param("severity", severity)
                .param("requestId", requestId).update();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return "";
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "FROZEN", "DELETED").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported account status");
        }
        return normalized;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record UserPage(
            List<UserSummary> items,
            String nextCursor,
            String previousCursor) {
        public UserPage {
            items = List.copyOf(items);
        }
    }

    public record UserSummary(
            UUID userId,
            String username,
            String displayName,
            String status,
            boolean administrator,
            long activeSessionCount,
            OffsetDateTime createdAt) {
    }

    public record UserDetails(
            UUID userId,
            String username,
            String displayName,
            String status,
            boolean administrator,
            String credentialState,
            long activeSessionCount,
            long activeAuthorizationCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime frozenAt,
            OffsetDateTime passwordChangedAt,
            OffsetDateTime lockedUntil) {
    }

    private record LockedTarget(String status, boolean administrator) {
    }
}
