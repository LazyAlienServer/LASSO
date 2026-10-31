package org.las.sso.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.las.sso.api.ApiProblems;
import org.las.sso.identity.LasUserPrincipal;
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
                && !isAllowed(ApiProblems.path(request))) {
            if (ApiProblems.isApi(request)) {
                ApiProblems.write(request, response, 403, "PASSWORD_CHANGE_REQUIRED", "必须先修改密码");
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
