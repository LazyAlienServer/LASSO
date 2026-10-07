package org.las.sso.security;

import java.time.OffsetDateTime;
import org.springframework.security.core.AuthenticationException;

public final class ClientAuthenticationRateLimitException extends AuthenticationException {

    private final OffsetDateTime resetsAt;

    public ClientAuthenticationRateLimitException(OffsetDateTime resetsAt) {
        super("Client authentication is temporarily rate limited");
        this.resetsAt = resetsAt;
    }

    public OffsetDateTime resetsAt() {
        return resetsAt;
    }
}
