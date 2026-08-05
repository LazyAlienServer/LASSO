package org.las.sso.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.session.UserSessionService;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class AccountSessionValidationFilter extends OncePerRequestFilter {

    private final JdbcClient jdbcClient;
    private final UserSessionService userSessions;

    public AccountSessionValidationFilter(JdbcClient jdbcClient, UserSessionService userSessions) {
        this.jdbcClient = jdbcClient;
        this.userSessions = userSessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)
                || (isCurrent(principal) && userSessions.validateOrRegister(principal, request))) {
            filterChain.doFilter(request, response);
            return;
        }

        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"SESSION_EXPIRED\"}");
        } else {
            response.sendRedirect(request.getContextPath() + "/login?sessionExpired");
        }
    }

    private boolean isCurrent(LasUserPrincipal principal) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                      FROM users u
                      JOIN password_credentials pc ON pc.user_id = u.id
                     WHERE u.id = :userId
                       AND u.account_status = 'ACTIVE'
                       AND pc.state IN ('ACTIVE', 'PASSWORD_CHANGE_REQUIRED')
                       AND pc.credentials_version = :credentialsVersion
                )
                """).param("userId", principal.userId())
                .param("credentialsVersion", principal.credentialsVersion())
                .query(Boolean.class).single();
    }
}
