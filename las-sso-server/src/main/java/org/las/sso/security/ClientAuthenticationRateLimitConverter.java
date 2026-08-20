package org.las.sso.security;

import java.time.Duration;
import java.time.OffsetDateTime;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

public final class ClientAuthenticationRateLimitConverter implements AuthenticationConverter {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int CLIENT_LIMIT = 10;
    private static final int IP_LIMIT = 20;

    private final PostgresRateLimiter rateLimiter;
    private final ClientNetworkKey clientNetworkKey;

    public ClientAuthenticationRateLimitConverter(
            PostgresRateLimiter rateLimiter,
            ClientNetworkKey clientNetworkKey) {
        this.rateLimiter = rateLimiter;
        this.clientNetworkKey = clientNetworkKey;
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        var clientObservation = rateLimiter.current(
                "CLIENT_SECRET_FAILURE_CLIENT",
                ClientAuthenticationFailureHandler.clientId(request),
                CLIENT_LIMIT,
                WINDOW);
        var ipObservation = rateLimiter.current(
                "CLIENT_SECRET_FAILURE_IP",
                clientNetworkKey.fromRemoteAddress(request.getRemoteAddr()),
                IP_LIMIT,
                WINDOW);
        if (clientObservation.thresholdReached() || ipObservation.thresholdReached()) {
            OffsetDateTime resetsAt = clientObservation.resetsAt().isAfter(ipObservation.resetsAt())
                    ? clientObservation.resetsAt()
                    : ipObservation.resetsAt();
            throw new ClientAuthenticationRateLimitException(resetsAt);
        }
        return null;
    }
}
