package org.las.sso.client;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Registers the verified, fixation-protected local session before invoking the application's
 * redirect.
 */
public final class LasSsoAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final LasSsoSessionRegistration registration;
    private final AuthenticationSuccessHandler delegate;

    public LasSsoAuthenticationSuccessHandler(
            LasSsoClientProperties properties,
            LasSsoBackChannelSessionStore sessions,
            LasSsoCurrentApplicationLogoutHandler logout,
            LasSsoClientErrorHandler errors,
            AuthenticationSuccessHandler delegate) {
        this.registration = new LasSsoSessionRegistration(properties, sessions, logout, errors);
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        if (registration.registerLogin(authentication, request, response)) {
            LasSsoReturnTo.verified(request);
            delegate.onAuthenticationSuccess(request, response, authentication);
        }
    }
}
