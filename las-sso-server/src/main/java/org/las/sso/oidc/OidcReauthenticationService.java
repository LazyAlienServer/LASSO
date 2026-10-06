package org.las.sso.oidc;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationException;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.session.UserSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** One pending, session-bound password challenge; never stores or replays a business command. */
@Service
public final class OidcReauthenticationService {

    private static final String SESSION_ATTRIBUTE = OidcReauthenticationService.class.getName() + ".PENDING";
    private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    private final Clock clock;
    private final JdbcClient jdbcClient;

    @Autowired
    public OidcReauthenticationService(JdbcClient jdbcClient) {
        this(jdbcClient, Clock.systemUTC());
    }

    OidcReauthenticationService(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    public static void validateMaxAge(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken authentication = context.getAuthentication();
        Object value = authentication.getAdditionalParameters().get("max_age");
        if (value == null) return;
        try {
            if (!(value instanceof String text) || !text.matches("[0-9]{1,19}")) {
                throw new NumberFormatException();
            }
            Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST, "Invalid max_age", null), authentication);
        }
    }

    public String begin(HttpServletRequest request, LasUserPrincipal principal) {
        Map<String, List<String>> parameters = parameters(request);
        HttpSession session = request.getSession(true);
        Pending current = pending(session);
        String subject = principal == null ? null : principal.getName();
        String sid = sid(session);
        if (current != null && !current.completed() && current.parameters().equals(parameters)
                && java.util.Objects.equals(current.subject(), subject)
                && java.util.Objects.equals(current.sid(), sid)) {
            return current.id();
        }
        var created = new Pending(UUID.randomUUID().toString(), subject, sid, parameters,
                clock.instant().plus(CHALLENGE_LIFETIME), false);
        if (current != null) {
            jdbcClient.sql("DELETE FROM oidc_password_challenges WHERE id = :id")
                    .param("id", UUID.fromString(current.id())).update();
        }
        jdbcClient.sql("INSERT INTO oidc_password_challenges (id, expires_at) VALUES (:id, :expiresAt)")
                .param("id", UUID.fromString(created.id()))
                .param("expiresAt", OffsetDateTime.ofInstant(created.expiresAt(), ZoneOffset.UTC)).update();
        session.setAttribute(SESSION_ATTRIBUTE, created);
        return created.id();
    }

    public void requireChallenge(HttpServletRequest request, LasUserPrincipal principal, String id) {
        Pending pending = pending(request.getSession(false));
        if (pending == null || pending.completed() || !pending.id().equals(id)
                || !principal.getName().equals(pending.subject())
                || !java.util.Objects.equals(sid(request.getSession(false)), pending.sid())
                || !jdbcClient.sql("""
                        SELECT EXISTS (SELECT 1 FROM oidc_password_challenges
                         WHERE id = :id AND expires_at > :now AND completed_at IS NULL)
                        """).param("id", UUID.fromString(pending.id())).param("now", now())
                        .query(Boolean.class).single()) {
            throw new RecentAuthenticationException("REAUTHENTICATION_CHALLENGE_EXPIRED");
        }
    }

    public String complete(HttpServletRequest request, LasUserPrincipal principal, String id) {
        requireChallenge(request, principal, id);
        HttpSession session = request.getSession(false);
        Pending pending = pending(session);
        markCompleted(pending, principal, sid(session));
        session.setAttribute(SESSION_ATTRIBUTE, pending.completed(principal.getName(), sid(session)));
        return returnPath(request, pending);
    }

    public void completeAfterLogin(HttpServletRequest request, LasUserPrincipal principal) {
        HttpSession session = request.getSession(false);
        Pending pending = pending(session);
        if (pending != null && pending.subject() == null && !pending.completed()) {
            markCompleted(pending, principal, sid(session));
            session.setAttribute(SESSION_ATTRIBUTE, pending.completed(principal.getName(), sid(session)));
        }
    }

    public boolean consumeCompletion(HttpServletRequest request, LasUserPrincipal principal) {
        HttpSession session = request.getSession(false);
        Pending pending = pending(session);
        if (pending == null || !pending.completed() || !pending.parameters().equals(parameters(request))
                || !principal.getName().equals(pending.subject())
                || !java.util.Objects.equals(sid(session), pending.sid())
                || !(session.getAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE) instanceof Instant at)
                || at.isAfter(clock.instant())) {
            return false;
        }
        session.removeAttribute(SESSION_ATTRIBUTE);
        return jdbcClient.sql("""
                UPDATE oidc_password_challenges SET consumed_at = :now
                 WHERE id = :id AND expires_at > :now
                   AND completed_at IS NOT NULL AND consumed_at IS NULL
                   AND subject = :subject AND sid = :sid
                """).param("now", now()).param("id", UUID.fromString(pending.id()))
                .param("subject", principal.userId()).param("sid", sid(session)).update() == 1;
    }

    private void markCompleted(Pending pending, LasUserPrincipal principal, String sid) {
        int updated = jdbcClient.sql("""
                UPDATE oidc_password_challenges
                   SET completed_at = :now, subject = :subject, sid = :sid
                 WHERE id = :id AND expires_at > :now AND completed_at IS NULL
                """).param("now", now()).param("subject", principal.userId()).param("sid", sid)
                .param("id", UUID.fromString(pending.id())).update();
        if (updated != 1) throw new RecentAuthenticationException("REAUTHENTICATION_CHALLENGE_EXPIRED");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private Pending pending(HttpSession session) {
        if (session == null || !(session.getAttribute(SESSION_ATTRIBUTE) instanceof Pending pending)) return null;
        if (!pending.expiresAt().isAfter(clock.instant())) {
            session.removeAttribute(SESSION_ATTRIBUTE);
            return null;
        }
        return pending;
    }

    private static Map<String, List<String>> parameters(HttpServletRequest request) {
        var parameters = new LinkedHashMap<String, List<String>>();
        request.getParameterMap().forEach((name, values) -> {
            // Spring's request-cache navigation flag is not part of the OIDC request.
            if (!"continue".equals(name)) parameters.put(name, List.copyOf(Arrays.asList(values)));
        });
        return Map.copyOf(parameters);
    }

    private static String sid(HttpSession session) {
        if (session == null) return null;
        Object value = session.getAttribute(UserSessionService.SID_SESSION_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }

    private static String returnPath(HttpServletRequest request, Pending pending) {
        var uri = UriComponentsBuilder.fromPath(request.getContextPath() + "/oauth2/authorize");
        pending.parameters().forEach((name, values) -> values.forEach(value ->
                uri.queryParam(UriUtils.encode(name, StandardCharsets.UTF_8),
                        UriUtils.encode(value, StandardCharsets.UTF_8))));
        return uri.build(true).toUriString();
    }

    private record Pending(String id, String subject, String sid, Map<String, List<String>> parameters,
                           Instant expiresAt, boolean completed) implements Serializable {
        Pending completed(String subject, String sid) {
            return new Pending(id, subject, sid, parameters, expiresAt, true);
        }
    }
}
