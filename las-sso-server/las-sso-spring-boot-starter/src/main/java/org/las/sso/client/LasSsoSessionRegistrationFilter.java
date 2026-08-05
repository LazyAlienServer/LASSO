package org.las.sso.client;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

final class LasSsoSessionRegistrationFilter extends OncePerRequestFilter {

    private final LasSsoBackChannelSessionRegistry sessions;

    LasSsoSessionRegistrationFilter(LasSsoBackChannelSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OidcUser user) {
            String sid = user.getClaimAsString("sid");
            sessions.register(sid, user.getSubject(), request.getSession(true));
        }
        filterChain.doFilter(request, response);
    }
}
