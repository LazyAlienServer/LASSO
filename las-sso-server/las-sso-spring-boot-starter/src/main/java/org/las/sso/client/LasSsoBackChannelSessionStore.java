package org.las.sso.client;

import jakarta.servlet.http.HttpSession;

/**
 * Public session-index boundary for Back-Channel Logout.
 *
 * <p>The default starter implementation is process-local. A consuming BFF may
 * replace it with a durable store keyed by the verified issuer/client/session
 * identity; the starter never owns the consuming application's schema.</p>
 */
public interface LasSsoBackChannelSessionStore {

    /**
     * Register a verified identity before login success is returned; repeated requests may call this again.
     * Return only after the index is usable by invalidate; throw on failure. This SPI does not provide
     * a distributed transaction with OAuth2 Token/session persistence. The default registry retains
     * process-local sid revocation markers; replacement stores must coordinate invalidate/register
     * themselves to prevent late registration after a completed Logout, including no-match invalidation.
     */
    void register(String sid, String subject, HttpSession session);

    /**
     * Idempotently end every currently indexed matching session. Return only when the operation completed,
     * including already-ended/no-match cases. Throw on incomplete execution and retain any live session's
     * index for retry; never report success merely because lookup entries were removed. Persistent adapters
     * own crash/transaction handling; the replay receipt does not itself make their changes atomic.
     */
    int invalidate(String sid, String subject);

    /** Idempotent index removal; does not invalidate or recreate the local session. */
    void unregister(String sessionId);
}
