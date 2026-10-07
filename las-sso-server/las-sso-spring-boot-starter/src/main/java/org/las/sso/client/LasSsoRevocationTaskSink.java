package org.las.sso.client;

import org.springframework.security.oauth2.client.registration.ClientRegistration;

/** Public submission boundary for reliable remote token-revocation work. */
@FunctionalInterface
public interface LasSsoRevocationTaskSink {

    /**
     * Accepts the token snapshot only after local authorization removal and session invalidation. A
     * normal return means the adapter accepted the work, not that the OP has revoked it. Durable
     * adapters must commit acceptance before returning; a submission failure must be reported as an
     * exception. The starter ends local login even when submission fails and records only a
     * non-secret failure category. The default queue remains process-local; it does not supply a
     * durability guarantee or schema for consumers. A crash before acceptance is not recoverable by
     * this interface; consumers need independently retained recovery intent or supported OP-side
     * revocation compensation. Never restore local authentication or claim remote success on
     * failure.
     */
    void submit(ClientRegistration registration, String token, String tokenTypeHint);
}
