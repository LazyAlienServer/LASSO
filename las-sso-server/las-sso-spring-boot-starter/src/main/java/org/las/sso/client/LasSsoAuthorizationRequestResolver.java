package org.las.sso.client;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** Adds local-only transaction attributes to the standard PKCE resolver, never OP parameters. */
final class LasSsoAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String CREATED_ATTRIBUTE =
            LasSsoAuthorizationRequestResolver.class.getName() + ".created";
    private final OAuth2AuthorizationRequestResolver delegate;
    private final Clock clock;

    LasSsoAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate, Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return attach(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return attach(request, delegate.resolve(request, registrationId));
    }

    private OAuth2AuthorizationRequest attach(
            HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
        if (authorization == null) return null;
        try {
            String target = LasSsoReturnTo.parameter(request);
            return OAuth2AuthorizationRequest.from(authorization)
                    .attributes(
                            attributes -> {
                                attributes.put(CREATED_ATTRIBUTE, clock.instant());
                                if (target != null)
                                    attributes.put(LasSsoReturnTo.AUTH_ATTRIBUTE, target);
                            })
                    .build();
        } catch (IllegalArgumentException exception) {
            throw new OAuth2AuthorizationException(
                    new OAuth2Error("invalid_request", "Invalid return target", null));
        }
    }
}
