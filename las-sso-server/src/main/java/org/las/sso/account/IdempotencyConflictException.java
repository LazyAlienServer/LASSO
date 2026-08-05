package org.las.sso.account;

public final class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("IDEMPOTENCY_KEY_REUSED");
    }
}
