package org.las.sso.account;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.las.sso.identity.UuidV7Generator;
import org.las.sso.security.RecentAuthenticationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    public static final Duration USERNAME_CHANGE_COOLDOWN = Duration.ofDays(90);

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final UuidV7Generator uuidGenerator;
    private final Clock clock;

    @Autowired
    public ProfileService(
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            UuidV7Generator uuidGenerator) {
        this(jdbcClient, passwordEncoder, uuidGenerator, Clock.systemUTC());
    }

    ProfileService(
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            UuidV7Generator uuidGenerator,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.uuidGenerator = uuidGenerator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public View profile(UUID userId) {
        return queryView(userId);
    }

    @Transactional
    public View updateDisplayName(UUID userId, String displayName, UUID requestId) {
        if (displayName == null || displayName.strip().isEmpty()) {
            throw new IllegalArgumentException("Display name required");
        }
        String normalized = displayName.strip();
        if (normalized.codePointCount(0, normalized.length()) > 64) {
            throw new IllegalArgumentException("Display name too long");
        }
        OffsetDateTime now = now();
        jdbcClient.sql("""
                UPDATE users
                   SET display_name = :displayName,
                       profile_version = profile_version + 1,
                       updated_at = :now
                 WHERE id = :userId
                """).param("displayName", normalized).param("now", now)
                .param("userId", userId).update();
        securityEvent(userId, "DISPLAY_NAME_UPDATED", "INFO", requestId, now, null);
        return queryView(userId);
    }

    @Transactional
    public View changeUsername(
            UUID userId, String newUsername, String currentPassword, UUID requestId) {
        String normalized = newUsername == null ? "" : newUsername.strip();
        if (!normalized.matches("[A-Za-z0-9_]{3,24}")) {
            throw new IllegalArgumentException("Invalid username");
        }
        OffsetDateTime now = now();
        String currentName = jdbcClient.sql("""
                SELECT un.username
                  FROM usernames un
                 WHERE un.user_id = :userId AND un.valid_to IS NULL
                """).param("userId", userId).query(String.class).single();
        if (normalized.equals(currentName)) {
            throw new IllegalArgumentException("Username unchanged");
        }
        String passwordHash = jdbcClient.sql("""
                SELECT password_hash FROM password_credentials WHERE user_id = :userId
                """).param("userId", userId).query(String.class).optional()
                .orElseThrow(() -> new RecentAuthenticationException("REAUTHENTICATION_FAILED"));
        if (currentPassword == null
                || !passwordEncoder.matches(currentPassword, passwordHash)) {
            throw new RecentAuthenticationException("REAUTHENTICATION_FAILED");
        }

        jdbcClient.sql("SELECT id FROM users WHERE id = :userId FOR UPDATE")
                .param("userId", userId).query(UUID.class).single();
        UUID currentUsernameId = jdbcClient.sql("""
                SELECT id FROM usernames
                 WHERE user_id = :userId AND valid_to IS NULL FOR UPDATE
                """).param("userId", userId).query(UUID.class).single();

        OffsetDateTime lastChange = jdbcClient.sql("""
                SELECT MAX(valid_from) FROM usernames
                 WHERE user_id = :userId
                   AND change_reason IN ('USERNAME_CHANGE', 'USERNAME_REUSE_HISTORICAL')
                """).param("userId", userId)
                .query(OffsetDateTime.class).optional().orElse(null);
        if (lastChange != null
                && lastChange.plus(USERNAME_CHANGE_COOLDOWN).isAfter(now)) {
            throw new UsernameCooldownException(
                    lastChange.plus(USERNAME_CHANGE_COOLDOWN));
        }

        UUID historicalId = jdbcClient.sql("""
                SELECT id FROM usernames
                 WHERE username = :username COLLATE "C"
                   AND user_id = :userId
                   AND valid_to IS NOT NULL
                """).param("username", normalized).param("userId", userId)
                .query(UUID.class).optional().orElse(null);
        boolean reused = historicalId != null;

        jdbcClient.sql("""
                UPDATE usernames SET valid_to = :now WHERE id = :currentId
                """).param("now", now).param("currentId", currentUsernameId).update();
        if (reused) {
            jdbcClient.sql("""
                    UPDATE usernames
                       SET valid_from = :now, valid_to = NULL,
                           changed_by_user_id = :userId,
                           change_reason = 'USERNAME_REUSE_HISTORICAL'
                     WHERE id = :historicalId
                    """).param("now", now).param("userId", userId)
                    .param("historicalId", historicalId).update();
        } else {
            try {
                jdbcClient.sql("""
                        INSERT INTO usernames
                            (id, user_id, username, valid_from,
                             changed_by_user_id, change_reason)
                        VALUES (:id, :userId, :username, :now, :userId, 'USERNAME_CHANGE')
                        """).param("id", uuidGenerator.generate())
                        .param("userId", userId).param("username", normalized)
                        .param("now", now).update();
            } catch (DuplicateKeyException exception) {
                throw new UsernameUnavailableException();
            }
        }
        jdbcClient.sql("""
                UPDATE users
                   SET profile_version = profile_version + 1, updated_at = :now
                 WHERE id = :userId
                """).param("now", now).param("userId", userId).update();
        securityEvent(userId, "USERNAME_CHANGED", "WARNING", requestId, now,
                jdbcClient.sql("""
                        SELECT jsonb_build_object('username', :username, 'reused', :reused)
                        """).param("username", normalized).param("reused", reused)
                        .query(String.class).single());
        return queryView(userId);
    }

    private View queryView(UUID userId) {
        return jdbcClient.sql("""
                SELECT u.display_name, un.username,
                       (SELECT MAX(valid_from) FROM usernames
                         WHERE user_id = u.id
                           AND change_reason IN ('USERNAME_CHANGE', 'USERNAME_REUSE_HISTORICAL'))
                         AS last_name_change
                  FROM users u
                  JOIN usernames un ON un.user_id = u.id AND un.valid_to IS NULL
                 WHERE u.id = :userId AND u.account_status = 'ACTIVE'
                """).param("userId", userId)
                .query((rs, rowNum) -> {
                    OffsetDateTime lastChange =
                            rs.getObject("last_name_change", OffsetDateTime.class);
                    OffsetDateTime availableAt = lastChange == null
                            ? null
                            : lastChange.plus(USERNAME_CHANGE_COOLDOWN);
                    return new View(
                            rs.getString("username"),
                            rs.getString("display_name"),
                            null,
                            availableAt);
                }).single();
    }

    private void securityEvent(
            UUID userId,
            String eventType,
            String severity,
            UUID requestId,
            OffsetDateTime now,
            String detailsSqlValue) {
        String details = detailsSqlValue == null ? "'{}'::jsonb" : "CAST(:details AS jsonb)";
        jdbcClient.sql("""
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity, request_id, details_json)
                VALUES (:id, :now, :userId, :eventType, :severity, :requestId, %s)
                """.formatted(details))
                .param("id", uuidGenerator.generate()).param("now", now)
                .param("userId", userId).param("eventType", eventType)
                .param("severity", severity).param("requestId", requestId)
                .param("details", detailsSqlValue).update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record View(
            String username,
            String displayName,
            String avatarUrl,
            OffsetDateTime usernameChangeAvailableAt) {
    }
}
