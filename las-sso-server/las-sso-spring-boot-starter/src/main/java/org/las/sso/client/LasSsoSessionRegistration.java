package org.las.sso.client;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Shared callback/request registration and failure cleanup; not a second authentication mechanism. */
final class LasSsoSessionRegistration {

    private static final Logger LOGGER = LoggerFactory.getLogger(LasSsoSessionRegistration.class);
    private final LasSsoClientProperties properties;
    private final LasSsoBackChannelSessionStore sessions;
    private final LasSsoCurrentApplicationLogoutHandler logout;
    private final LasSsoClientErrorHandler errors;

    LasSsoSessionRegistration(LasSsoClientProperties properties, LasSsoBackChannelSessionStore sessions,
            LasSsoCurrentApplicationLogoutHandler logout, LasSsoClientErrorHandler errors) {
        this.properties = properties;
        this.sessions = sessions;
        this.logout = logout;
        this.errors = errors;
    }

    boolean register(Authentication authentication, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        return register(authentication, request, response, false);
    }

    boolean registerLogin(Authentication authentication, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        return register(authentication, request, response, true);
    }

    private boolean register(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
            boolean replaceIdentity) throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2) || !oauth2.isAuthenticated()
                || !properties.registrationId().equals(oauth2.getAuthorizedClientRegistrationId())) return true;
        String sessionId = null;
        try {
            if (!(oauth2.getPrincipal() instanceof OidcUser user)
                    || user.getSubject() == null || user.getSubject().isBlank()
                    || user.getClaimAsString("sid") == null || user.getClaimAsString("sid").isBlank()) {
                throw new IllegalArgumentException("Verified LAS OIDC identity requires sid and subject");
            }
            var session = request.getSession(true);
            sessionId = session.getId();
            // Re-login may replace the sid/subject previously registered during session fixation handling.
            if (replaceIdentity) sessions.unregister(sessionId);
            sessions.register(user.getClaimAsString("sid"), user.getSubject(), session);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("LAS session registration failed: failureType={}", exception.getClass().getSimpleName());
            if (sessionId != null) {
                try {
                    sessions.unregister(sessionId);
                } catch (RuntimeException cleanupFailure) {
                    LOGGER.error("LAS session index cleanup failed: failureType={}",
                            cleanupFailure.getClass().getSimpleName());
                }
            }
            logout.logout(request, response, authentication);
            errors.handle(request, response, LasSsoClientErrorHandler.Failure.SESSION_REGISTRATION_FAILED);
            return false;
        }
    }
}
