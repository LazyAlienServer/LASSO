package org.las.sso.account;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.las.sso.identity.UuidV7Generator;
import org.las.sso.logout.BackChannelLogoutOutbox;
import org.las.sso.session.UserSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeletionRequestService {

    public static final Duration EXECUTION_DELAY = Duration.ofDays(30);

    private final JdbcClient jdbcClient;
    private final UserSessionService userSessions;
    private final BackChannelLogoutOutbox backChannelLogout;
    private final UuidV7Generator uuidGenerator;
    private final Clock clock;

    @Autowired
    public DeletionRequestService(
            JdbcClient jdbcClient,
            UserSessionService userSessions,
            BackChannelLogoutOutbox backChannelLogout,
            UuidV7Generator uuidGenerator) {
        this(jdbcClient, userSessions, backChannelLogout, uuidGenerator, Clock.systemUTC());
    }

    DeletionRequestService(
            JdbcClient jdbcClient,
            UserSessionService userSessions,
            BackChannelLogoutOutbox backChannelLogout,
            UuidV7Generator uuidGenerator,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.userSessions = userSessions;
        this.backChannelLogout = backChannelLogout;
        this.uuidGenerator = uuidGenerator;
        this.clock = clock;
    }

    @Transactional
    public RequestView request(UUID userId, UUID requestId) {
        OffsetDateTime now = now();
        TargetState target = jdbcClient.sql("""
                SELECT account_status::text, is_sso_admin
                  FROM users WHERE id = :userId FOR UPDATE
                """).param("userId", userId)
                .query((rs, rowNum) -> new TargetState(
                        rs.getString("account_status"), rs.getBoolean("is_sso_admin")))
                .optional()
                .orElseThrow(() -> new AccountDeletionConflictException("ACCOUNT_STATE_CONFLICT"));
        if (!"ACTIVE".equals(target.status())) {
            throw new AccountDeletionConflictException("ACCOUNT_STATE_CONFLICT");
        }
        if (target.administrator()) {
            throw new AccountDeletionConflictException("ADMIN_DELETION_FORBIDDEN");
        }
        OffsetDateTime executeAfter = now.plus(EXECUTION_DELAY);
        try {
            jdbcClient.sql("""
                    INSERT INTO account_deletion_requests
                        (id, user_id, status, requested_at, execute_after)
                    VALUES (:id, :userId, 'PENDING', :now, :executeAfter)
                    """).param("id", uuidGenerator.generate()).param("userId", userId)
                    .param("now", now).param("executeAfter", executeAfter).update();
        } catch (DuplicateKeyException exception) {
            throw new AccountDeletionConflictException("ACCOUNT_STATE_CONFLICT");
        }
        userSessions.logoutAll(userId, "DELETION_REQUESTED");
        jdbcClient.sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                .param("principalName", userId.toString()).update();
        jdbcClient.sql("""
                DELETE FROM oauth2_authorization_consent WHERE principal_name = :principalName
                """).param("principalName", userId.toString()).update();
        backChannelLogout.enqueueForUser(userId, "DELETION_REQUESTED", now);
        securityEvent(userId, "DELETION_REQUESTED", "WARNING", requestId, now);
        return new RequestView("PENDING", now, executeAfter, null, null);
    }

    @Transactional
    public void cancel(UUID userId, UUID requestId) {
        OffsetDateTime now = now();
        int updated = jdbcClient.sql("""
                UPDATE account_deletion_requests
                   SET status = 'CANCELLED', cancelled_at = :now
                 WHERE user_id = :userId
                   AND status = 'PENDING'
                """).param("now", now).param("userId", userId).update();
        if (updated == 0) {
            throw new AccountDeletionConflictException("ACCOUNT_STATE_CONFLICT");
        }
        securityEvent(userId, "DELETION_CANCELLED", "INFO", requestId, now);
    }

    @Transactional(readOnly = true)
    public RequestView status(UUID userId) {
        return jdbcClient.sql("""
                SELECT status::text, requested_at, execute_after, hold_until, hold_reason
                  FROM account_deletion_requests
                 WHERE user_id = :userId
                 ORDER BY requested_at DESC
                 LIMIT 1
                """).param("userId", userId)
                .query((rs, rowNum) -> new RequestView(
                        rs.getString("status"),
                        rs.getObject("requested_at", OffsetDateTime.class),
                        rs.getObject("execute_after", OffsetDateTime.class),
                        rs.getObject("hold_until", OffsetDateTime.class),
                        rs.getString("hold_reason")))
                .optional()
                .orElseGet(() -> new RequestView("NONE", null, null, null, null));
    }

    @Transactional
    public int executeDue(int limit) {
        List<ExecutionTarget> targets = jdbcClient.sql("""
                SELECT id, user_id
                  FROM account_deletion_requests
                 WHERE status = 'PENDING'
                   AND execute_after <= :now
                 ORDER BY execute_after
                 LIMIT :limit
                """).param("now", now()).param("limit", limit)
                .query((rs, rowNum) -> new ExecutionTarget(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class)))
                .list();
        for (ExecutionTarget target : targets) {
            execute(target.requestId(), target.userId());
        }
        return targets.size();
    }

    @Transactional
    public void execute(UUID requestId, UUID userId) {
        OffsetDateTime now = now();
        Boolean isAdmin = jdbcClient.sql("""
                SELECT is_sso_admin FROM users WHERE id = :userId FOR UPDATE
                """).param("userId", userId).query(Boolean.class).single();
        if (Boolean.TRUE.equals(isAdmin)) {
            jdbcClient.sql("""
                    INSERT INTO security_events
                        (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                    VALUES (:id, :now, :userId, 'DELETION_SKIPPED_ADMIN_PROMOTED',
                            'HIGH', :requestId, '{}'::jsonb)
                    """).param("id", uuidGenerator.generate()).param("now", now)
                    .param("userId", userId).param("requestId", uuidGenerator.generate()).update();
            return;
        }
        int claimed = jdbcClient.sql("""
                UPDATE account_deletion_requests
                   SET status = 'EXECUTED', executed_at = :now
                 WHERE id = :requestId
                   AND status = 'PENDING'
                """).param("now", now).param("requestId", requestId).update();
        if (claimed == 0) {
            return;
        }
        userSessions.logoutAll(userId, "ACCOUNT_DELETED");
        jdbcClient.sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                .param("principalName", userId.toString()).update();
        jdbcClient.sql("""
                DELETE FROM oauth2_authorization_consent WHERE principal_name = :principalName
                """).param("principalName", userId.toString()).update();
        jdbcClient.sql("DELETE FROM password_credentials WHERE user_id = :userId")
                .param("userId", userId).update();
        jdbcClient.sql("DELETE FROM recovery_codes WHERE user_id = :userId")
                .param("userId", userId).update();
        jdbcClient.sql("DELETE FROM admin_recovery_credentials WHERE target_user_id = :userId")
                .param("userId", userId).update();
        jdbcClient.sql("DELETE FROM recovery_sessions WHERE user_id = :userId")
                .param("userId", userId).update();
        jdbcClient.sql("""
                UPDATE users
                   SET account_status = 'DELETED', deleted_at = :now, updated_at = :now
                 WHERE id = :userId
                """).param("now", now).param("userId", userId).update();
        backChannelLogout.enqueueForUser(userId, "ACCOUNT_DELETED", now);
        securityEvent(userId, "ACCOUNT_DELETED", "HIGH", uuidGenerator.generate(), now);
    }

    private void securityEvent(
            UUID userId, String eventType, String severity, UUID requestId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                VALUES (:id, :now, :userId, :eventType, :severity, :requestId, '{}'::jsonb)
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("userId", userId).param("eventType", eventType).param("severity", severity)
                .param("requestId", requestId).update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record RequestView(
            String status,
            OffsetDateTime requestedAt,
            OffsetDateTime executeAfter,
            OffsetDateTime holdUntil,
            String holdReason) {
    }

    private record ExecutionTarget(UUID requestId, UUID userId) {
    }

    private record TargetState(String status, boolean administrator) {
    }
}
