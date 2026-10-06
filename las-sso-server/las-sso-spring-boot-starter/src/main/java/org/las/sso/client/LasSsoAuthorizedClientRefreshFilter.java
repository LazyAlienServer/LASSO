package org.las.sso.client;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.filter.OncePerRequestFilter;

public final class LasSsoAuthorizedClientRefreshFilter extends OncePerRequestFilter {

    private final LasSsoClientProperties properties;
    private final LasSsoAuthorizedClientRefresher refresher;
    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final Clock clock;
    private final LasSsoClientErrorHandler errors;

    public LasSsoAuthorizedClientRefreshFilter(
            LasSsoClientProperties properties,
            LasSsoAuthorizedClientRefresher refresher,
            OAuth2AuthorizedClientRepository authorizedClients) {
        this(properties, refresher, authorizedClients, Clock.systemUTC());
    }

    public LasSsoAuthorizedClientRefreshFilter(
            LasSsoClientProperties properties,
            LasSsoAuthorizedClientRefresher refresher,
            OAuth2AuthorizedClientRepository authorizedClients,
            LasSsoClientErrorHandler errors) {
        this(properties, refresher, authorizedClients, Clock.systemUTC(), errors);
    }

    LasSsoAuthorizedClientRefreshFilter(
            LasSsoClientProperties properties,
            LasSsoAuthorizedClientRefresher refresher,
            OAuth2AuthorizedClientRepository authorizedClients,
            Clock clock) {
        this(properties, refresher, authorizedClients, clock, new DefaultLasSsoClientErrorHandler());
    }

    private LasSsoAuthorizedClientRefreshFilter(
            LasSsoClientProperties properties,
            LasSsoAuthorizedClientRefresher refresher,
            OAuth2AuthorizedClientRepository authorizedClients,
            Clock clock,
            LasSsoClientErrorHandler errors) {
        this.properties = properties;
        this.refresher = refresher;
        this.authorizedClients = authorizedClients;
        this.clock = clock;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2)
                || !properties.registrationId().equals(oauth2.getAuthorizedClientRegistrationId())) {
            filterChain.doFilter(request, response);
            return;
        }

        var session = request.getSession(false);
        if (session != null
                && !java.time.Instant.ofEpochMilli(session.getCreationTime())
                        .plus(properties.sessionAbsoluteLifetime())
                        .isAfter(clock.instant())) {
            expireLocalSession(oauth2, request, response);
            errors.handle(request, response, LasSsoClientErrorHandler.Failure.SESSION_EXPIRED);
            return;
        }

        try {
            refresher.refresh(authentication, request, response);
            OAuth2AuthorizedClient authorizedClient = authorizedClients.loadAuthorizedClient(
                    properties.registrationId(), oauth2, request);
            requireFreshAccessToken(authorizedClient);
            filterChain.doFilter(request, response);
        } catch (OAuth2AuthorizationException exception) {
            if (isTerminalAuthorizationFailure(exception)) {
                expireLocalSession(oauth2, request, response);
                errors.handle(request, response, LasSsoClientErrorHandler.Failure.SESSION_EXPIRED);
            } else {
                errors.handle(request, response, LasSsoClientErrorHandler.Failure.SSO_UNAVAILABLE);
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/oauth2/")
                || path.startsWith("/login/oauth2/")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/logout")
                || path.equals("/api/auth/logout/global")
                || path.equals("/logged-out")
                || path.startsWith("/assets/")
                || path.equals("/error");
    }

    private void expireLocalSession(
            OAuth2AuthenticationToken authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        authorizedClients.removeAuthorizedClient(
                properties.registrationId(), authentication, request, response);
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // Another request in the same failed refresh family already invalidated it.
            }
        }
    }

    private static boolean isTerminalAuthorizationFailure(OAuth2AuthorizationException exception) {
        return switch (exception.getError().getErrorCode()) {
            case "invalid_grant", "invalid_token", "unauthorized_client", "authorized_client_missing",
                    "authorized_client_expired" -> true;
            default -> false;
        };
    }

    private void requireFreshAccessToken(OAuth2AuthorizedClient authorizedClient) {
        if (authorizedClient == null
                || authorizedClient.getAccessToken() == null
                || authorizedClient.getAccessToken().getExpiresAt() == null
                || !authorizedClient.getAccessToken().getExpiresAt().isAfter(clock.instant())) {
            throw new OAuth2AuthorizationException(new OAuth2Error(
                    "authorized_client_expired",
                    "The Access Token freshness boundary has expired.",
                    null));
        }
    }

}
