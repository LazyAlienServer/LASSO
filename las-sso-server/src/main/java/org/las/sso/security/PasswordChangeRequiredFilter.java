package org.las.sso.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.las.sso.identity.LasUserPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_PATHS =
            Set.of(
                    "/password-change-required",
                    "/api/v1/me/password-change",
                    "/logout",
                    "/login",
                    "/error",
                    "/actuator/health/liveness",
                    "/actuator/health/readiness");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof LasUserPrincipal principal
                && principal.passwordChangeRequired()
                && !isAllowed(request.getRequestURI())) {
            if (request.getRequestURI().startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
            } else {
                response.sendRedirect(request.getContextPath() + "/password-change-required");
            }
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isAllowed(String requestUri) {
        return ALLOWED_PATHS.contains(requestUri) || requestUri.startsWith("/assets/");
    }
}
