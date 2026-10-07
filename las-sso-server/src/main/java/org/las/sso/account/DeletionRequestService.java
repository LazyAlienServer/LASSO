package org.las.sso.account;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.las.sso.access.ServiceAccessEligibilityService;
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
    private final ServiceAccessEligibilityService serviceAccess;
    private final Clock clock;

    @Autowired
    public DeletionRequestService(
            JdbcClient jdbcClient,
            UserSessionService userSessions,
            BackChannelLogoutOutbox backChannelLogout,
            UuidV7Generator uuidGenerator,
            ServiceAccessEligibilityService serviceAccess) {
        this(
                jdbcClient,
                userSessions,
                backChannelLogout,
                uuidGenerator,
                serviceAccess,
                Clock.systemUTC());
    }

    DeletionRequestService(
            JdbcClient jdbcClient,
            UserSessionService userSessions,
            BackChannelLogoutOutbox backChannelLogout,
            UuidV7Generator uuidGenerator,
            ServiceAccessEligibilityService serviceAccess,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.userSessions = userSessions;
        this.backChannelLogout = backChannelLogout;
        this.uuidGenerator = uuidGenerator;
        this.serviceAccess = serviceAccess;
        this.clock = clock;
    }

    @Transactional
    public RequestView request(UUID userId, UUID requestId) {
        TargetState target =
                jdbcClient
                        .sql(
                                """
                SELECT account_status::text, is_sso_admin
                  FROM users WHERE id = :userId FOR UPDATE
                """)
                        .param("userId", userId)
                        .query(
                                (rs, rowNum) ->
                                        new TargetState(
                                                rs.getString("account_status"),
                                                rs.getBoolean("is_sso_admin")))
                        .optional()
                        .orElseThrow(
                                () ->
                                        new AccountDeletionConflictException(
                                                "ACCOUNT_STATE_CONFLICT"));
        if (!"ACTIVE".equals(target.status())) {
            throw new AccountDeletionConflictException("ACCOUNT_STATE_CONFLICT");
        }
        if (target.administrator()) {
            throw new AccountDeletionConflictException("ADMIN_DELETION_FORBIDDEN");
        }
        OffsetDateTime now = now();
        OffsetDateTime executeAfter = now.plus(EXECUTION_DELAY);
        try {
            jdbcClient
                    .sql(
                            """
                    INSERT INTO account_deletion_requests
                        (id, user_id, status, requested_at, execute_after)
                    VALUES (:id, :userId, 'PENDING', :now, :executeAfter)
                    """)
                    .param("id", uuidGenerator.generate())
                    .param("userId", userId)
                    .param("now", now)
                    .param("executeAfter", executeAfter)
                    .update();
        } catch (DuplicateKeyException exception) {
            throw new AccountDeletionConflictException("ACCOUNT_STATE_CONFLICT");
        }
        serviceAccess.advanceStateVersion(userId, now);
        userSessions.logoutAll(userId, "DELETION_REQUESTED");
        jdbcClient
                .sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                .param("principalName", userId.toString())
                .update();
        jdbcClient
                .sql(
                        """
                DELETE FROM oauth2_authorization_consent WHERE principal_name = :principalName
                """)
                .param("principalName", userId.toString())
                .update();
        backChannelLogout.enqueueForUser(userId, "DELETION_REQUESTED", now);
        securityEvent(userId, "DELETION_REQUESTED", "WARNING", requestId, now);
        return new RequestView("PENDING", now, executeAfter, null, null);
    }

    @Transactional
    public void cancel(UUID userId, UUID requestId) {
        jdbcClient
                .sql("SELECT id FROM users WHERE id = :userId FOR UPDATE")
                .param("userId", userId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new AccountDeletionConflictException("ACCOUNT_STATE_CONFLICT"));
        OffsetDateTime now = now();
        int updated =
                jdbcClient
                        .sql(
                                """
                UPDATE account_deletion_requests
                   SET status = 'CANCELLED', cancelled_at = :now,
                       hold_until = NULL, held_by_user_id = NULL, hold_reason = NULL,
                       hold_public_summary = NULL
                 WHERE user_id = :userId
                   AND status IN ('PENDING', 'HELD')
                """)
                        .param("now", now)
                        .param("userId", userId)
                        .update();
        if (updated == 0) {
            throw new AccountDeletionConflictException("ACCOUNT_STATE_CONFLICT");
        }
        serviceAccess.advanceStateVersion(userId, now);
        securityEvent(userId, "DELETION_CANCELLED", "INFO", requestId, now);
    }

    @Transactional(readOnly = true)
    public RequestView status(UUID userId) {
        return jdbcClient
                .sql(
                        """
                SELECT status::text, requested_at, execute_after, hold_until, hold_public_summary
                  FROM account_deletion_requests
                 WHERE user_id = :userId
                 ORDER BY requested_at DESC
                 LIMIT 1
                """)
                .param("userId", userId)
                .query(
                        (rs, rowNum) ->
                                new RequestView(
                                        rs.getString("status"),
                                        rs.getObject("requested_at", OffsetDateTime.class),
                                        rs.getObject("execute_after", OffsetDateTime.class),
                                        rs.getObject("hold_until", OffsetDateTime.class),
                                        rs.getString("hold_public_summary")))
                .optional()
                .orElseGet(() -> new RequestView("NONE", null, null, null, null));
    }

    @Transactional
    public int executeDue(int limit) {
        validateLimit(limit);
        OffsetDateTime now = now();
        List<ExecutionTarget> targets = pendingCandidates(now, limit);
        Set<UUID> lockedUsers = lockCandidateUsers(targets);
        int processed = 0;
        for (ExecutionTarget target : targets) {
            if (lockedUsers.contains(target.userId())
                    && executeLocked(target.requestId(), target.userId(), now(), true)) {
                processed++;
            }
        }
        return processed;
    }

    /**
     * One transaction for both scheduled phases; all candidate user locks precede request locks.
     */
    @Transactional
    public int processDueBatch(int limit) {
        return processDueBatch(now(), limit);
    }

    @Transactional
    public int processDueBatch(OffsetDateTime cutoff, int limit) {
        validateLimit(limit);
        OffsetDateTime selectedAt = java.util.Objects.requireNonNull(cutoff, "cutoff");
        var expired = expiredHoldCandidates(selectedAt, limit);
        var pending = pendingCandidates(selectedAt, limit);
        var all = new java.util.ArrayList<>(expired);
        all.addAll(pending);
        Set<UUID> lockedUsers = lockCandidateUsers(all);
        int released = releaseLockedHolds(expired, lockedUsers, selectedAt);
        if (all.isEmpty() || lockedUsers.isEmpty()) return released;
        // Include only preselected requests/users; never acquire a new user after the first phase.
        var due =
                jdbcClient
                        .sql(
                                """
                SELECT id, user_id FROM account_deletion_requests
                 WHERE id IN (:requestIds) AND user_id IN (:userIds)
                   AND status = 'PENDING' AND execute_after <= :now
                 ORDER BY execute_after, id LIMIT :limit
                """)
                        .param("requestIds", all.stream().map(ExecutionTarget::requestId).toList())
                        .param("userIds", lockedUsers)
                        .param("now", now())
                        .param("limit", limit)
                        .query(
                                (rs, row) ->
                                        new ExecutionTarget(
                                                rs.getObject("id", UUID.class),
                                                rs.getObject("user_id", UUID.class)))
                        .list();
        int processed = 0;
        for (ExecutionTarget target : due) {
            if (executeLocked(target.requestId(), target.userId(), now(), true)) processed++;
        }
        return released + processed;
    }

    @Transactional
    public int releaseExpiredHolds(OffsetDateTime cutoff, int limit) {
        validateLimit(limit);
        var targets = expiredHoldCandidates(cutoff, limit);
        return releaseLockedHolds(targets, lockCandidateUsers(targets), cutoff);
    }

    private List<ExecutionTarget> pendingCandidates(OffsetDateTime cutoff, int limit) {
        return jdbcClient
                .sql(
                        """
                SELECT id, user_id
                  FROM account_deletion_requests
                 WHERE status = 'PENDING'
                   AND execute_after <= :now
                  ORDER BY execute_after, id
                 LIMIT :limit
                """)
                .param("now", cutoff)
                .param("limit", limit)
                .query(
                        (rs, rowNum) ->
                                new ExecutionTarget(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class)))
                .list();
    }

    private List<ExecutionTarget> expiredHoldCandidates(OffsetDateTime cutoff, int limit) {
        return jdbcClient
                .sql(
                        """
                SELECT id, user_id FROM account_deletion_requests
                 WHERE status = 'HELD' AND hold_until IS NOT NULL AND hold_until < :now
                 ORDER BY hold_until, id LIMIT :limit
                """)
                .param("now", cutoff)
                .param("limit", limit)
                .query(
                        (rs, row) ->
                                new ExecutionTarget(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class)))
                .list();
    }

    private Set<UUID> lockCandidateUsers(List<ExecutionTarget> targets) {
        if (targets.isEmpty()) return Set.of();
        // PostgreSQL UUID order is authoritative (Java UUID's signed comparator is different).
        var users =
                jdbcClient
                        .sql(
                                """
                SELECT id FROM users WHERE id IN (:userIds) ORDER BY id FOR UPDATE SKIP LOCKED
                """)
                        .param(
                                "userIds",
                                targets.stream().map(ExecutionTarget::userId).distinct().toList())
                        .query(UUID.class)
                        .list();
        return Set.copyOf(users);
    }

    private int releaseLockedHolds(
            List<ExecutionTarget> targets, Set<UUID> lockedUsers, OffsetDateTime cutoff) {
        int released = 0;
        for (ExecutionTarget target : targets) {
            if (!lockedUsers.contains(target.userId())) continue;
            RequestState request = lockRequest(target.requestId(), target.userId(), true);
            if (request == null
                    || !"HELD".equals(request.status())
                    || request.holdUntil() == null
                    || !request.holdUntil().isBefore(cutoff)) continue;
            jdbcClient
                    .sql(
                            """
                    UPDATE account_deletion_requests SET status = 'PENDING', hold_until = NULL,
                        held_by_user_id = NULL, hold_reason = NULL, hold_public_summary = NULL
                     WHERE id = :id AND user_id = :userId AND status = 'HELD' AND hold_until < :now
                    """)
                    .param("id", target.requestId())
                    .param("userId", target.userId())
                    .param("now", cutoff)
                    .update();
            serviceAccess.advanceStateVersion(target.userId(), cutoff);
            securityEvent(
                    target.userId(),
                    "DELETION_HOLD_EXPIRED",
                    "INFO",
                    uuidGenerator.generate(),
                    cutoff);
            released++;
        }
        return released;
    }

    @Transactional
    public void execute(UUID requestId, UUID userId) {
        jdbcClient
                .sql("SELECT id FROM users WHERE id = :userId FOR UPDATE")
                .param("userId", userId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new AccountDeletionConflictException("ACCOUNT_STATE_CONFLICT"));
        executeLocked(requestId, userId, now(), false);
    }

    private boolean executeLocked(
            UUID requestId, UUID userId, OffsetDateTime now, boolean skipLocked) {
        RequestState request = lockRequest(requestId, userId, skipLocked);
        if (request == null
                || !"PENDING".equals(request.status())
                || request.executeAfter().isAfter(now)) return false;
        Boolean isAdmin =
                jdbcClient
                        .sql("SELECT is_sso_admin FROM users WHERE id = :userId")
                        .param("userId", userId)
                        .query(Boolean.class)
                        .single();
        if (Boolean.TRUE.equals(isAdmin)) {
            int cancelled =
                    jdbcClient
                            .sql(
                                    """
                    UPDATE account_deletion_requests
                       SET status = 'CANCELLED', cancelled_at = :now
                      WHERE id = :requestId AND user_id = :userId AND status = 'PENDING' AND execute_after <= :now
                    """)
                            .param("now", now)
                            .param("requestId", requestId)
                            .param("userId", userId)
                            .update();
            if (cancelled == 0) {
                return false;
            }
            serviceAccess.advanceStateVersion(userId, now);
            jdbcClient
                    .sql(
                            """
                    INSERT INTO security_events
                        (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                    VALUES (:id, :now, :userId, 'DELETION_SKIPPED_ADMIN_PROMOTED',
                            'HIGH', :requestId, '{}'::jsonb)
                    """)
                    .param("id", uuidGenerator.generate())
                    .param("now", now)
                    .param("userId", userId)
                    .param("requestId", uuidGenerator.generate())
                    .update();
            return true;
        }
        int claimed =
                jdbcClient
                        .sql(
                                """
                UPDATE account_deletion_requests
                   SET status = 'EXECUTED', executed_at = :now
                  WHERE id = :requestId
                    AND user_id = :userId AND execute_after <= :now
                   AND status = 'PENDING'
                """)
                        .param("now", now)
                        .param("requestId", requestId)
                        .param("userId", userId)
                        .update();
        if (claimed == 0) {
            return false;
        }
        userSessions.logoutAll(userId, "ACCOUNT_DELETED");
        jdbcClient
                .sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                .param("principalName", userId.toString())
                .update();
        jdbcClient
                .sql(
                        """
                DELETE FROM oauth2_authorization_consent WHERE principal_name = :principalName
                """)
                .param("principalName", userId.toString())
                .update();
        jdbcClient
                .sql("DELETE FROM password_credentials WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbcClient
                .sql("DELETE FROM recovery_codes WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbcClient
                .sql("DELETE FROM admin_recovery_credentials WHERE target_user_id = :userId")
                .param("userId", userId)
                .update();
        jdbcClient
                .sql("DELETE FROM recovery_sessions WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbcClient
                .sql(
                        """
                UPDATE users
                   SET account_status = 'DELETED', deleted_at = :now,
                       display_name = '已注销用户', avatar_resource_id = NULL,
                       profile_version = profile_version + 1, updated_at = :now
                 WHERE id = :userId
                """)
                .param("now", now)
                .param("userId", userId)
                .update();
        serviceAccess.advanceStateVersion(userId, now);
        backChannelLogout.enqueueForUser(userId, "ACCOUNT_DELETED", now);
        securityEvent(userId, "ACCOUNT_DELETED", "HIGH", uuidGenerator.generate(), now);
        return true;
    }

    private RequestState lockRequest(UUID requestId, UUID userId, boolean skipLocked) {
        return jdbcClient
                .sql(
                        """
                SELECT status::text, execute_after, hold_until FROM account_deletion_requests
                 WHERE id = :requestId AND user_id = :userId FOR UPDATE
                """
                                + (skipLocked ? " SKIP LOCKED" : ""))
                .param("requestId", requestId)
                .param("userId", userId)
                .query(
                        (rs, row) ->
                                new RequestState(
                                        rs.getString("status"),
                                        rs.getObject("execute_after", OffsetDateTime.class),
                                        rs.getObject("hold_until", OffsetDateTime.class)))
                .optional()
                .orElse(null);
    }

    private static void validateLimit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Deletion batch size must be positive");
    }

    private void securityEvent(
            UUID userId, String eventType, String severity, UUID requestId, OffsetDateTime now) {
        jdbcClient
                .sql(
                        """
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                VALUES (:id, :now, :userId, :eventType, :severity, :requestId, '{}'::jsonb)
                """)
                .param("id", uuidGenerator.generate())
                .param("now", now)
                .param("userId", userId)
                .param("eventType", eventType)
                .param("severity", severity)
                .param("requestId", requestId)
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record RequestView(
            String status,
            OffsetDateTime requestedAt,
            OffsetDateTime executeAfter,
            OffsetDateTime holdUntil,
            String holdPublicSummary) {}

    private record ExecutionTarget(UUID requestId, UUID userId) {}

    private record RequestState(
            String status, OffsetDateTime executeAfter, OffsetDateTime holdUntil) {}

    private record TargetState(String status, boolean administrator) {}
}
