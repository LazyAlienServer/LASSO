package org.las.sso.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;

final class SingleFlightLasSsoAuthorizedClientRefresher implements LasSsoAuthorizedClientRefresher {

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final LasSsoClientProperties properties;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inFlight =
            new ConcurrentHashMap<>();

    SingleFlightLasSsoAuthorizedClientRefresher(
            OAuth2AuthorizedClientManager authorizedClientManager,
            LasSsoClientProperties properties) {
        this.authorizedClientManager = authorizedClientManager;
        this.properties = properties;
    }

    @Override
    public void refresh(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String key = refreshKey(authentication, request);
        var owned = new CompletableFuture<Void>();
        var existing = inFlight.putIfAbsent(key, owned);
        if (existing != null) {
            await(existing);
            return;
        }

        try {
            var authorizeRequest =
                    OAuth2AuthorizeRequest.withClientRegistrationId(properties.registrationId())
                            .principal(authentication)
                            .attribute(HttpServletRequest.class.getName(), request)
                            .attribute(HttpServletResponse.class.getName(), response)
                            .build();
            if (authorizedClientManager.authorize(authorizeRequest) == null) {
                throw new OAuth2AuthorizationException(
                        new OAuth2Error(
                                "authorized_client_missing",
                                "The server-side OAuth2 authorized client is missing.",
                                null));
            }
            owned.complete(null);
        } catch (RuntimeException exception) {
            owned.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key, owned);
        }
    }

    private static String refreshKey(Authentication authentication, HttpServletRequest request) {
        var session = request.getSession(false);
        String owner = session == null ? authentication.getName() : session.getId();
        return owner + '\n' + authentication.getName();
    }

    private static void await(CompletableFuture<Void> existing) {
        try {
            existing.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }
}
