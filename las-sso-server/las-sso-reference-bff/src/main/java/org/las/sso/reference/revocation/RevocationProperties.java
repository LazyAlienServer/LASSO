package org.las.sso.reference.revocation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit external settings for the reference-only profile; no development credentials/key
 * defaults.
 */
@ConfigurationProperties("reference.revocation")
public record RevocationProperties(
        String jdbcUrl,
        String username,
        String password,
        String keyId,
        String keyBase64,
        boolean allowLoopbackHttp) {

    public RevocationProperties {
        if (jdbcUrl == null
                || !jdbcUrl.startsWith("jdbc:postgresql:")
                || username == null
                || username.isBlank()
                || password == null
                || password.isBlank()
                || keyId == null
                || !keyId.matches("[A-Za-z0-9._-]{1,64}")
                || keyBase64 == null
                || keyBase64.isBlank()) {
            throw new IllegalArgumentException(
                    "Explicit PostgreSQL and revocation encryption settings are required");
        }
    }

    @Override
    public String toString() {
        return "RevocationProperties[redacted]";
    }
}
