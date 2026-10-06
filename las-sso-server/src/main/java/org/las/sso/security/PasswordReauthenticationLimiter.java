package org.las.sso.security;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import org.las.sso.identity.LasUserPrincipal;
import org.springframework.stereotype.Service;

@Service
public final class PasswordReauthenticationLimiter {

    private final PostgresRateLimiter rateLimiter;
    private final ClientNetworkKey clientNetworkKey;

    public PasswordReauthenticationLimiter(PostgresRateLimiter rateLimiter, ClientNetworkKey clientNetworkKey) {
        this.rateLimiter = rateLimiter;
        this.clientNetworkKey = clientNetworkKey;
    }

    public void consume(LasUserPrincipal principal, HttpServletRequest request) {
        String networkKey = clientNetworkKey.fromRemoteAddress(request.getRemoteAddr());
        var ip = rateLimiter.consume("REAUTHENTICATION_IP", networkKey, 10, Duration.ofMinutes(15));
        var user = rateLimiter.consume(
                "REAUTHENTICATION_USER", principal.userId().toString(), 10, Duration.ofMinutes(15));
        if (!ip.allowed() || !user.allowed()) {
            throw new RateLimitExceededException(
                    ip.resetsAt().isAfter(user.resetsAt()) ? ip.resetsAt() : user.resetsAt());
        }
    }
}
