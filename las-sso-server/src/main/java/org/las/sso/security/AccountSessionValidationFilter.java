package org.las.sso.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!isCurrent(principal)) {
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
            return;
        }
        userSessions.validateOrRegister(principal, request);
        if (!isDeletionRestricted(principal.userId())
                || isDeletionScopeAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"DELETION_PENDING\"}");
        } else {
            response.sendRedirect(request.getContextPath() + "/account/delete");
        }
    }

    private boolean isDeletionRestricted(UUID userId) {
        Boolean restricted = jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1
                      FROM account_deletion_requests
                     WHERE user_id = :userId
                       AND status IN ('PENDING', 'HELD')
                )
                """).param("userId", userId).query(Boolean.class).single();
        return Boolean.TRUE.equals(restricted);
    }

    private static boolean isDeletionScopeAllowed(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        if (uri.equals("/account/delete")) {
            return true;
        }
        if (uri.startsWith("/assets/") || uri.equals("/favicon.ico")) {
            return true;
        }
        if (uri.equals("/api/v1/me/deletion-request")) {
            return method.equals("GET") || method.equals("DELETE");
        }
        if (uri.equals("/logout")) {
            return true;
        }
        if (uri.equals("/error")) {
            return true;
        }
        return false;
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
