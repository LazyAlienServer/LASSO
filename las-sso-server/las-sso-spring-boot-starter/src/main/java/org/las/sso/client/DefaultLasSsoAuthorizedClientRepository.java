package org.las.sso.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;

/** Default single-instance implementation; persistence belongs to the consumer BFF. */
public final class DefaultLasSsoAuthorizedClientRepository
        implements LasSsoAuthorizedClientRepository {

    private final HttpSessionOAuth2AuthorizedClientRepository delegate =
            new HttpSessionOAuth2AuthorizedClientRepository();

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId, Authentication principal, HttpServletRequest request) {
        return delegate.loadAuthorizedClient(clientRegistrationId, principal, request);
    }

    @Override
    public void saveAuthorizedClient(
            OAuth2AuthorizedClient authorizedClient,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        delegate.saveAuthorizedClient(authorizedClient, principal, request, response);
    }

    @Override
    public void removeAuthorizedClient(
            String clientRegistrationId,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        delegate.removeAuthorizedClient(clientRegistrationId, principal, request, response);
    }
}
