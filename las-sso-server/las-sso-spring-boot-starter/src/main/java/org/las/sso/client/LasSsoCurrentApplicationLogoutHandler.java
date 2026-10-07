package org.las.sso.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.authentication.logout.LogoutHandler;

public final class LasSsoCurrentApplicationLogoutHandler implements LogoutHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LasSsoCurrentApplicationLogoutHandler.class);

    private final ClientRegistrationRepository registrations;
    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final LasSsoClientProperties properties;
    private final LasSsoRevocationTaskSink revocations;

    public LasSsoCurrentApplicationLogoutHandler(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            LasSsoClientProperties properties,
            LasSsoRevocationTaskSink revocations) {
        this.registrations = registrations;
        this.authorizedClients = authorizedClients;
        this.properties = properties;
        this.revocations = revocations;
    }

    /**
     * Compatibility constructor for existing starter consumers; new code should inject the sink.
     */
    public LasSsoCurrentApplicationLogoutHandler(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            LasSsoClientProperties properties,
            LasSsoTokenRevocationQueue revocations) {
        this(registrations, authorizedClients, properties, revocations::enqueue);
    }

    @Override
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        if (authentication == null) {
            return;
        }
        OAuth2AuthorizedClient authorizedClient = null;
        try {
            authorizedClient =
                    authorizedClients.loadAuthorizedClient(
                            properties.registrationId(), authentication, request);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Unable to read local authorization for logout: failureType={}",
                    exception.getClass().getSimpleName());
        }

        try {
            authorizedClients.removeAuthorizedClient(
                    properties.registrationId(), authentication, request, response);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Unable to remove local authorization; local session will end: failureType={}",
                    exception.getClass().getSimpleName());
        } finally {
            SecurityContextHolder.clearContext();
            var session = request.getSession(false);
            if (session != null) {
                try {
                    session.invalidate();
                } catch (IllegalStateException ignored) {
                    // A concurrent logout already ended this local session.
                }
            }
        }

        // Use only the snapshot from this session, after local authentication has ended.
        if (authorizedClient == null) {
            return;
        }
        try {
            var registration = registrations.findByRegistrationId(properties.registrationId());
            if (registration != null && authorizedClient.getRefreshToken() != null) {
                revocations.submit(
                        registration,
                        authorizedClient.getRefreshToken().getTokenValue(),
                        "refresh_token");
            } else if (registration != null && authorizedClient.getAccessToken() != null) {
                revocations.submit(
                        registration,
                        authorizedClient.getAccessToken().getTokenValue(),
                        "access_token");
            }
        } catch (RuntimeException exception) {
            // A failed submission is not remote revocation success. Do not log secret-bearing
            // errors.
            LOGGER.error(
                    "Remote token revocation submission failed; local logout is complete: failureType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
