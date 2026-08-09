package org.las.sso.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationException;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.security.SameOriginVerifier;
import org.las.sso.session.UserSessionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Enforces the database-backed invariants required for dangerous administrator writes. */
@Component
public final class AdminOperationGuard {

    private final JdbcClient jdbcClient;
    private final SameOriginVerifier sameOriginVerifier;
    private final RecentAuthenticationService recentAuthentication;
    private final UserSessionService userSessions;

    public AdminOperationGuard(
            JdbcClient jdbcClient,
            SameOriginVerifier sameOriginVerifier,
            RecentAuthenticationService recentAuthentication,
            UserSessionService userSessions) {
        this.jdbcClient = jdbcClient;
        this.sameOriginVerifier = sameOriginVerifier;
        this.recentAuthentication = recentAuthentication;
        this.userSessions = userSessions;
    }

    public Context authorizeWrite(Authentication authentication, HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)
                || principal.getAuthorities().stream()
                        .noneMatch(authority -> "ROLE_SSO_ADMIN".equals(authority.getAuthority()))) {
            throw new AdminUserManagementException("ADMIN_ACCESS_DENIED");
        }
        String sid = userSessions.currentSid(request);
        boolean currentAdministratorSession = sid != null && Boolean.TRUE.equals(jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                      FROM users u
                      JOIN user_sessions s ON s.user_id = u.id
                      JOIN password_credentials pc ON pc.user_id = u.id
                      JOIN system_security_state state ON state.id = 1
                     WHERE u.id = :userId
                       AND u.account_status = 'ACTIVE'
                       AND u.is_sso_admin = true
                       AND s.sid = :sid
                       AND s.status = 'ACTIVE'
                       AND s.admin_session = true
                       AND s.idle_expires_at > CURRENT_TIMESTAMP
                       AND s.absolute_expires_at > CURRENT_TIMESTAMP
                       AND s.credentials_version = pc.credentials_version
                       AND s.security_epoch = state.security_epoch
                )
                """).param("userId", principal.userId()).param("sid", sid)
                .query(Boolean.class).single());
        if (!currentAdministratorSession) {
            throw new AdminUserManagementException("ADMIN_ACCESS_DENIED");
        }
        if (!recentAuthentication.isRecent(request)) {
            throw new RecentAuthenticationException("RECENT_AUTHENTICATION_REQUIRED");
        }
        return new Context(principal, sid);
    }

    public record Context(LasUserPrincipal principal, String sid) {
    }
}
