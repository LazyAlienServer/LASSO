package org.las.sso.security;

import java.time.OffsetDateTime;

public final class RateLimitExceededException extends RuntimeException {

    private final OffsetDateTime resetsAt;

    public RateLimitExceededException(OffsetDateTime resetsAt) {
        super("RATE_LIMITED");
        this.resetsAt = resetsAt;
    }

    public OffsetDateTime resetsAt() {
        return resetsAt;
    }
}
