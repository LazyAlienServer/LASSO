package org.las.sso.client;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Servlet-session destruction and ID-change hook, also reusable by an application-owned chain. */
public final class LasSsoSessionLifecycleListener implements HttpSessionListener, HttpSessionIdListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(LasSsoSessionLifecycleListener.class);
    private final LasSsoBackChannelSessionStore sessions;
    private final LasSsoClientProperties properties;

    public LasSsoSessionLifecycleListener(LasSsoBackChannelSessionStore sessions, LasSsoClientProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        sessions.unregister(event.getSession().getId());
    }

    @Override
    public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
        try {
            sessions.unregister(oldSessionId);
            Object context = event.getSession().getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (context instanceof SecurityContext security
                    && security.getAuthentication() instanceof OAuth2AuthenticationToken oauth2
                    && properties.registrationId().equals(oauth2.getAuthorizedClientRegistrationId())
                    && oauth2.getPrincipal() instanceof OidcUser user) {
                sessions.register(user.getClaimAsString("sid"), user.getSubject(), event.getSession());
            }
        } catch (RuntimeException exception) {
            LOGGER.error("LAS session ID reindex failed: failureType={}", exception.getClass().getSimpleName());
            // There is no response to render in a listener; end the session if reindexing fails.
            event.getSession().invalidate();
            throw new IllegalStateException("LAS session ID reindex failed; local session ended");
        }
        // Initial login may not have a stored SecurityContext yet; the success handler registers it next.
    }
}
