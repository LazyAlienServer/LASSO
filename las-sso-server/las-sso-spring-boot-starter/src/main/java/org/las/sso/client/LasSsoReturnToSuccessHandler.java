package org.las.sso.client;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/** Explicit final redirect hook; business checks must run before invoking it in an application-owned handler. */
public final class LasSsoReturnToSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthenticationSuccessHandler fallback;

    public LasSsoReturnToSuccessHandler(AuthenticationSuccessHandler fallback) {
        this.fallback = java.util.Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        var target = LasSsoReturnTo.target(request);
        if (target.isEmpty()) {
            fallback.onAuthenticationSuccess(request, response, authentication);
            return;
        }
        new HttpSessionRequestCache().removeRequest(request, response);
        var session = request.getSession(false);
        if (session != null) session.removeAttribute("SPRING_SECURITY_LAST_EXCEPTION");
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(request.getContextPath() + LasSsoReturnTo.validate(target.get()));
    }
}
