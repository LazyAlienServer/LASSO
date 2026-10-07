package org.las.sso.reference.revocation;

import java.util.UUID;

/**
 * Persisted snapshots contain encrypted Token material and no client secret. Never log task
 * contents.
 */
record RevocationTask(
        UUID id,
        String registrationId,
        String issuer,
        String clientId,
        String uri,
        String hint,
        String keyId,
        byte[] nonce,
        byte[] ciphertext,
        UUID claimToken,
        int attempts) {
    @Override
    public String toString() {
        return "RevocationTask[redacted]";
    }
}
