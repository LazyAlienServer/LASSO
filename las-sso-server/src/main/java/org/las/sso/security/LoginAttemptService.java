package org.las.sso.security;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.las.sso.identity.UuidV7Generator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginAttemptService {

    private final JdbcClient jdbcClient;
    private final UuidV7Generator uuidGenerator;
    private final Clock clock;
    private final org.las.sso.observability.SsoMetrics metrics;

    @Autowired
    public LoginAttemptService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator,
            org.las.sso.observability.SsoMetrics metrics) {
        this(jdbcClient, uuidGenerator, Clock.systemUTC(), metrics);
    }

    LoginAttemptService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator,
            Clock clock,
            org.las.sso.observability.SsoMetrics metrics) {
        this.jdbcClient = jdbcClient;
        this.uuidGenerator = uuidGenerator;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public void recordFailure(String username) {
        AttemptState state =
                jdbcClient
                        .sql(
                                """
                SELECT pc.user_id, pc.failed_attempts, pc.last_failed_at, u.is_sso_admin
                  FROM password_credentials pc
                  JOIN users u ON u.id = pc.user_id
                  JOIN usernames un ON un.user_id = u.id AND un.valid_to IS NULL
                 WHERE un.username = :username COLLATE "C"
                 FOR UPDATE OF pc
                """)
                        .param("username", username)
                        .query(
                                (rs, rowNum) ->
                                        new AttemptState(
                                                rs.getObject("user_id", UUID.class),
                                                rs.getInt("failed_attempts"),
                                                rs.getObject(
                                                        "last_failed_at", OffsetDateTime.class),
                                                rs.getBoolean("is_sso_admin")))
                        .optional()
                        .orElse(null);
        if (state == null) {
            return;
        }
        if (state.admin()) metrics.committedAdminLoginFailure();

        OffsetDateTime now = now();
        Duration window = state.admin() ? Duration.ofMinutes(15) : Duration.ofMinutes(30);
        int threshold = state.admin() ? 5 : 10;
        int failures =
                state.lastFailedAt() == null || state.lastFailedAt().isBefore(now.minus(window))
                        ? 1
                        : state.failures() + 1;
        OffsetDateTime lockedUntil =
                failures >= threshold
                        ? now.plus(state.admin() ? Duration.ofMinutes(30) : Duration.ofMinutes(15))
                        : null;
        jdbcClient
                .sql(
                        """
                UPDATE password_credentials
                   SET failed_attempts = :failures,
                       last_failed_at = :now,
                       locked_until = :lockedUntil
                 WHERE user_id = :userId
                """)
                .param("failures", failures)
                .param("now", now)
                .param("lockedUntil", lockedUntil, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("userId", state.userId())
                .update();
        if (lockedUntil != null) {
            jdbcClient
                    .sql(
                            """
                    INSERT INTO security_events
                        (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                    VALUES (:id, :now, :userId, 'ACCOUNT_TEMPORARILY_LOCKED', :severity,
                            :requestId, jsonb_build_object('failedAttempts', :failures))
                    """)
                    .param("id", uuidGenerator.generate())
                    .param("now", now)
                    .param("userId", state.userId())
                    .param("severity", state.admin() ? "HIGH" : "WARNING")
                    .param("requestId", uuidGenerator.generate())
                    .param("failures", failures)
                    .update();
        }
    }

    @Transactional
    public void recordSuccess(UUID userId) {
        jdbcClient
                .sql(
                        """
                UPDATE password_credentials
                   SET failed_attempts = 0, last_failed_at = NULL, locked_until = NULL
                 WHERE user_id = :userId
                """)
                .param("userId", userId)
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record AttemptState(
            UUID userId, int failures, OffsetDateTime lastFailedAt, boolean admin) {}
}
