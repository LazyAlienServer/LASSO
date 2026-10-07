package org.las.sso.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Single-session atomic consumption of the standard Spring request, with local transaction expiry.
 */
final class LasSsoAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final Duration LIFETIME = Duration.ofMinutes(10);
    private final HttpSessionOAuth2AuthorizationRequestRepository delegate =
            new HttpSessionOAuth2AuthorizationRequestRepository();
    private final Clock clock;

    LasSsoAuthorizationRequestRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        synchronized (session) {
            var authorization = delegate.loadAuthorizationRequest(request);
            return valid(request, authorization) ? authorization : null;
        }
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorization,
            HttpServletRequest request,
            HttpServletResponse response) {
        var session = request.getSession(authorization != null);
        if (session == null) return;
        synchronized (session) {
            delegate.saveAuthorizationRequest(authorization, request, response);
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        request.removeAttribute(LasSsoReturnTo.CONSUMED_ATTRIBUTE);
        var session = request.getSession(false);
        if (session == null) return null;
        synchronized (session) {
            String[] states = request.getParameterValues("state");
            if (states == null || states.length != 1) return null;
            var authorization = delegate.removeAuthorizationRequest(request, response);
            if (!valid(request, authorization)) return null;
            // The provider still performs code/state/nonce/signature validation after removal.
            Object target = authorization.getAttribute(LasSsoReturnTo.AUTH_ATTRIBUTE);
            if (target instanceof String value)
                request.setAttribute(LasSsoReturnTo.CONSUMED_ATTRIBUTE, value);
            return authorization;
        }
    }

    private boolean valid(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
        String[] states = request.getParameterValues("state");
        if (states == null || states.length != 1 || authorization == null) return false;
        Object created =
                authorization.getAttribute(LasSsoAuthorizationRequestResolver.CREATED_ATTRIBUTE);
        Instant now = clock.instant();
        return created instanceof Instant issued
                && !issued.isAfter(now)
                && issued.plus(LIFETIME).isAfter(now);
    }
}
