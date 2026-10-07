package org.las.sso.client;

import java.time.Instant;

/** Public replaceable boundary for validated Back-Channel Logout replay state. */
public interface LasSsoLogoutReplayStore {

    /**
     * Records a validated token ID and its content digest.
     *
     * @return true for a first or identical replay, false for conflicting content
     */
    boolean accept(String issuer, String tokenId, String contentDigest, Instant expiresAt);

    /**
     * Atomically reserves issuer/jti plus digest, executes at most one action at a time, and caches
     * success. Identical successful replays return SUCCESS without running the action. A failed
     * action must release its execution reservation but retain the digest until retainUntil so
     * retry is possible and conflicts stay rejected. IN_PROGRESS is retryable, never a successful
     * receipt. retainUntil includes clock skew.
     *
     * <p>Existing accept-only adapters remain source compatible but must implement this method
     * before receiving logout traffic. The default fails closed; accept alone cannot attest
     * execution success. Durable adapters own transaction/lease/crash recovery and idempotent
     * execution, not this interface.
     */
    default Result process(
            String issuer,
            String tokenId,
            String contentDigest,
            Instant retainUntil,
            Runnable action) {
        throw new UnsupportedOperationException(
                "Logout replay adapter must implement execution receipts");
    }

    enum Result {
        SUCCESS,
        CONFLICT,
        IN_PROGRESS
    }
}
