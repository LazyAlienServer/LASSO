package org.las.sso.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.identity.UuidV7Generator;
import org.las.sso.logout.BackChannelLogoutOutbox;
import org.las.sso.security.ClientNetworkKey;
import org.las.sso.security.TransientSecurityKeyHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserSessionService {

    public static final String SID_SESSION_ATTRIBUTE = UserSessionService.class.getName() + ".SID";
    private static final Duration USER_IDLE = Duration.ofHours(24);
    private static final Duration USER_ABSOLUTE = Duration.ofDays(14);
    private static final Duration REMEMBERED_IDLE = Duration.ofDays(14);
    private static final Duration REMEMBERED_ABSOLUTE = Duration.ofDays(90);
    private static final Duration ADMIN_IDLE = Duration.ofMinutes(45);
    private static final Duration ADMIN_ABSOLUTE = Duration.ofHours(10);
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcClient jdbcClient;
    private final UuidV7Generator uuidGenerator;
    private final ClientNetworkKey networkKey;
    private final TransientSecurityKeyHasher keyHasher;
    private final BackChannelLogoutOutbox backChannelLogout;
    private final Clock clock;

    @Autowired
    public UserSessionService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator,
            ClientNetworkKey networkKey,
            TransientSecurityKeyHasher keyHasher,
            BackChannelLogoutOutbox backChannelLogout) {
        this(
                jdbcClient,
                uuidGenerator,
                networkKey,
                keyHasher,
                backChannelLogout,
                Clock.systemUTC());
    }

    UserSessionService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator,
            ClientNetworkKey networkKey,
            TransientSecurityKeyHasher keyHasher,
            BackChannelLogoutOutbox backChannelLogout,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.uuidGenerator = uuidGenerator;
        this.networkKey = networkKey;
        this.keyHasher = keyHasher;
        this.backChannelLogout = backChannelLogout;
        this.clock = clock;
    }

    @Transactional
    public String registerLogin(LasUserPrincipal principal, HttpServletRequest request) {
        HttpSession httpSession = request.getSession(true);
        Object existing = httpSession.getAttribute(SID_SESSION_ATTRIBUTE);
        if (existing instanceof String sid && !sid.isBlank()) {
            return sid;
        }

        OffsetDateTime now = now();
        boolean administrator =
                principal.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_SSO_ADMIN".equals(authority.getAuthority()));
        boolean remembered =
                !administrator && "true".equals(request.getParameter("rememberDevice"));
        Duration idleDuration =
                administrator ? ADMIN_IDLE : remembered ? REMEMBERED_IDLE : USER_IDLE;
        Duration absoluteDuration =
                administrator ? ADMIN_ABSOLUTE : remembered ? REMEMBERED_ABSOLUTE : USER_ABSOLUTE;
        httpSession.setMaxInactiveInterval(Math.toIntExact(idleDuration.toSeconds()));
        String sid = oidcSessionIdHash(httpSession.getId());
        String securityEpoch =
                jdbcClient
                        .sql("SELECT security_epoch FROM system_security_state WHERE id = 1")
                        .query(String.class)
                        .single();
        String network = networkKey.fromRemoteAddress(request.getRemoteAddr());
        byte[] ipPrefixHash = keyHasher.hash("user-session-ip", network);
        String userAgent = summarizeUserAgent(request.getHeader("User-Agent"));

        jdbcClient
                .sql(
                        """
                INSERT INTO user_sessions
                    (id, sid, user_id, status, remembered, admin_session,
                     credentials_version, security_epoch, created_at, last_seen_at,
                     idle_expires_at, absolute_expires_at, last_password_auth_at,
                     ip_prefix_hash, user_agent_summary)
                VALUES (:id, :sid, :userId, 'ACTIVE', :remembered, :administrator,
                        :credentialsVersion, :securityEpoch, :now, :now,
                        :idleExpiresAt, :absoluteExpiresAt, :now,
                        :ipPrefixHash, :userAgent)
                """)
                .param("id", uuidGenerator.generate())
                .param("sid", sid)
                .param("userId", principal.userId())
                .param("remembered", remembered)
                .param("administrator", administrator)
                .param("credentialsVersion", principal.credentialsVersion())
                .param("securityEpoch", securityEpoch)
                .param("now", now)
                .param("idleExpiresAt", now.plus(idleDuration))
                .param("absoluteExpiresAt", now.plus(absoluteDuration))
                .param("ipPrefixHash", ipPrefixHash)
                .param("userAgent", userAgent)
                .update();
        httpSession.setAttribute(SID_SESSION_ATTRIBUTE, sid);
        return sid;
    }

    @Transactional
    public boolean validateOrRegister(LasUserPrincipal principal, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(SID_SESSION_ATTRIBUTE) instanceof String sid)
                || sid.isBlank()) {
            registerLogin(principal, request);
            return true;
        }

        OffsetDateTime now = now();
        int updated =
                jdbcClient
                        .sql(
                                """
                UPDATE user_sessions s
                   SET last_seen_at = CASE
                         WHEN s.last_seen_at <= :touchBefore THEN :now
                         ELSE s.last_seen_at END,
                       idle_expires_at = CASE
                         WHEN s.last_seen_at <= :touchBefore
                         THEN LEAST(:nextIdleExpiry, s.absolute_expires_at)
                         ELSE s.idle_expires_at END
                  FROM users u, password_credentials pc, system_security_state state
                 WHERE s.sid = :sid
                   AND s.user_id = :userId
                   AND s.status = 'ACTIVE'
                   AND s.idle_expires_at > :now
                   AND s.absolute_expires_at > :now
                   AND s.credentials_version = :credentialsVersion
                   AND s.credentials_version = pc.credentials_version
                   AND s.security_epoch = state.security_epoch
                   AND u.id = s.user_id
                   AND pc.user_id = s.user_id
                   AND u.account_status = 'ACTIVE'
                   AND pc.state IN ('ACTIVE', 'PASSWORD_CHANGE_REQUIRED')
                """)
                        .param("touchBefore", now.minus(TOUCH_INTERVAL))
                        .param("now", now)
                        .param("nextIdleExpiry", now.plus(idleDuration(principal, session)))
                        .param("sid", sid)
                        .param("userId", principal.userId())
                        .param("credentialsVersion", principal.credentialsVersion())
                        .update();
        if (updated == 0) {
            expireIfNecessary(sid, now);
            return false;
        }
        return true;
    }

    @Transactional
    public void updateRecentPasswordAuthentication(HttpServletRequest request) {
        String sid = currentSid(request);
        if (sid != null) {
            jdbcClient
                    .sql(
                            """
                    UPDATE user_sessions
                       SET last_password_auth_at = :now
                     WHERE sid = :sid AND status = 'ACTIVE'
                    """)
                    .param("now", now())
                    .param("sid", sid)
                    .update();
        }
    }

    @Transactional(readOnly = true)
    public List<SessionView> sessions(UUID userId, HttpServletRequest request) {
        String currentSid = currentSid(request);
        OffsetDateTime now = now();
        return jdbcClient
                .sql(
                        """
                SELECT sid, remembered, admin_session, created_at, last_seen_at,
                       idle_expires_at, absolute_expires_at, user_agent_summary
                  FROM user_sessions
                 WHERE user_id = :userId
                   AND status = 'ACTIVE'
                   AND idle_expires_at > :now
                   AND absolute_expires_at > :now
                 ORDER BY CASE WHEN sid = :currentSid THEN 0 ELSE 1 END,
                          last_seen_at DESC
                """)
                .param("userId", userId)
                .param("now", now)
                .param("currentSid", currentSid == null ? "" : currentSid)
                .query(
                        (rs, rowNum) -> {
                            OffsetDateTime lastSeen =
                                    rs.getObject("last_seen_at", OffsetDateTime.class);
                            OffsetDateTime idleExpiry =
                                    rs.getObject("idle_expires_at", OffsetDateTime.class);
                            OffsetDateTime absoluteExpiry =
                                    rs.getObject("absolute_expires_at", OffsetDateTime.class);
                            OffsetDateTime expiry =
                                    idleExpiry.isBefore(absoluteExpiry)
                                            ? idleExpiry
                                            : absoluteExpiry;
                            String sid = rs.getString("sid");
                            return new SessionView(
                                    sid,
                                    sid.equals(currentSid),
                                    rs.getBoolean("remembered"),
                                    rs.getBoolean("admin_session"),
                                    rs.getString("user_agent_summary"),
                                    lastSeen,
                                    expiry,
                                    DISPLAY_TIME.format(lastSeen),
                                    DISPLAY_TIME.format(expiry));
                        })
                .list();
    }

    @Transactional
    public RevocationResult revoke(UUID userId, String sid, HttpServletRequest request) {
        String currentSid = currentSid(request);
        if (sid != null && sid.equals(currentSid)) {
            logoutAll(userId, "CURRENT_SESSION_REVOKED");
            return RevocationResult.CURRENT;
        }
        int updated =
                jdbcClient
                        .sql(
                                """
                UPDATE user_sessions
                   SET status = 'REVOKED', revoked_at = :now, revoke_reason = 'USER_REVOKED'
                 WHERE user_id = :userId AND sid = :sid AND status = 'ACTIVE'
                """)
                        .param("now", now())
                        .param("userId", userId)
                        .param("sid", sid)
                        .update();
        if (updated != 0) {
            OffsetDateTime now = now();
            backChannelLogout.enqueueForSession(userId, sid, "USER_REVOKED", now);
            jdbcClient
                    .sql(
                            """
                    DELETE FROM oauth2_authorization a
                     USING oauth2_authorization_security security
                     WHERE a.id = security.authorization_id
                       AND a.principal_name = :principalName
                       AND security.sid = :sid
                    """)
                    .param("principalName", userId.toString())
                    .param("sid", sid)
                    .update();
        }
        return updated == 0 ? RevocationResult.NOT_FOUND : RevocationResult.OTHER;
    }

    @Transactional
    public void logoutAll(UUID userId, String reason) {
        OffsetDateTime now = now();
        jdbcClient
                .sql(
                        """
                UPDATE user_sessions
                   SET status = 'REVOKED', revoked_at = :now, revoke_reason = :reason
                 WHERE user_id = :userId AND status = 'ACTIVE'
                """)
                .param("now", now)
                .param("reason", reason)
                .param("userId", userId)
                .update();
        backChannelLogout.enqueueForUser(userId, reason, now);
        jdbcClient
                .sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                .param("principalName", userId.toString())
                .update();
        jdbcClient
                .sql(
                        """
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload_json,
                     created_at, available_at)
                VALUES (:id, 'USER', :userId, 'GLOBAL_LOGOUT_REQUESTED',
                        jsonb_build_object('userId', :userId, 'reason', :reason),
                        :now, :now)
                """)
                .param("id", uuidGenerator.generate())
                .param("userId", userId.toString())
                .param("reason", reason)
                .param("now", now)
                .update();
    }

    public String currentSid(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object sid = session.getAttribute(SID_SESSION_ATTRIBUTE);
        return sid instanceof String value && !value.isBlank() ? value : null;
    }

    static String oidcSessionIdHash(String sessionId) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(sessionId.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void expireIfNecessary(String sid, OffsetDateTime now) {
        jdbcClient
                .sql(
                        """
                UPDATE user_sessions
                   SET status = 'EXPIRED', revoked_at = :now, revoke_reason = 'SESSION_EXPIRED'
                 WHERE sid = :sid AND status = 'ACTIVE'
                   AND (idle_expires_at <= :now OR absolute_expires_at <= :now)
                """)
                .param("now", now)
                .param("sid", sid)
                .update();
    }

    private static Duration idleDuration(LasUserPrincipal principal, HttpSession session) {
        boolean administrator =
                principal.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_SSO_ADMIN".equals(authority.getAuthority()));
        if (administrator) return ADMIN_IDLE;
        if (session.getAttribute(SID_SESSION_ATTRIBUTE) instanceof String) {
            // The persistent row remains authoritative; this branch avoids an extra read
            // for ordinary sessions and keeps remembered sessions sliding for 30 days.
            return session.getMaxInactiveInterval() > USER_IDLE.toSeconds()
                    ? REMEMBERED_IDLE
                    : USER_IDLE;
        }
        return USER_IDLE;
    }

    private static String summarizeUserAgent(String value) {
        if (value == null || value.isBlank()) {
            return "未知浏览器";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String browser;
        if (lower.contains("edg/")) browser = "Microsoft Edge";
        else if (lower.contains("firefox/")) browser = "Firefox";
        else if (lower.contains("chrome/")) browser = "Chrome";
        else if (lower.contains("safari/")) browser = "Safari";
        else browser = "其他浏览器";

        String system;
        if (lower.contains("windows")) system = "Windows";
        else if (lower.contains("android")) system = "Android";
        else if (lower.contains("iphone") || lower.contains("ipad")) system = "iOS";
        else if (lower.contains("mac os")) system = "macOS";
        else if (lower.contains("linux")) system = "Linux";
        else system = "未知系统";
        return browser + " · " + system;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public enum RevocationResult {
        CURRENT,
        OTHER,
        NOT_FOUND
    }

    public record SessionView(
            String sid,
            boolean current,
            boolean remembered,
            boolean administrator,
            String userAgentSummary,
            OffsetDateTime lastSeenAt,
            OffsetDateTime expiresAt,
            String lastSeenLabel,
            String expiresLabel) {}
}
