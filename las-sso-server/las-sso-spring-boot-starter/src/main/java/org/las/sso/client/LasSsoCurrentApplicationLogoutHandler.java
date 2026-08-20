package org.las.sso.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.logout.LogoutHandler;

public final class LasSsoCurrentApplicationLogoutHandler implements LogoutHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LasSsoCurrentApplicationLogoutHandler.class);

    private final ClientRegistrationRepository registrations;
    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final LasSsoClientProperties properties;
    private final LasSsoTokenRevocationQueue revocations;

    public LasSsoCurrentApplicationLogoutHandler(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            LasSsoClientProperties properties,
            LasSsoTokenRevocationQueue revocations) {
        this.registrations = registrations;
        this.authorizedClients = authorizedClients;
        this.properties = properties;
        this.revocations = revocations;
    }

    @Override
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        if (authentication == null) {
            return;
        }
        try {
            var authorizedClient = authorizedClients.loadAuthorizedClient(
                    properties.registrationId(), authentication, request);
            if (authorizedClient != null) {
                var registration = registrations.findByRegistrationId(properties.registrationId());
                if (registration != null && authorizedClient.getRefreshToken() != null) {
                    revocations.enqueue(
                            registration,
                            authorizedClient.getRefreshToken().getTokenValue(),
                            "refresh_token");
                } else if (registration != null && authorizedClient.getAccessToken() != null) {
                    revocations.enqueue(
                            registration,
                            authorizedClient.getAccessToken().getTokenValue(),
                            "access_token");
                }
                authorizedClients.removeAuthorizedClient(
                        properties.registrationId(), authentication, request, response);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to enqueue remote token revocation; local logout will continue", exception);
        }
    }
}
