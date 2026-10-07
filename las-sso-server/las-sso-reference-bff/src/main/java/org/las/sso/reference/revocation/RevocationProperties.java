package org.las.sso.reference.revocation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
        boolean allowLoopbackHttp,
        @DefaultValue("P30D") Duration completedRetention,
        @DefaultValue("100") int cleanupBatchSize) {

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
        if (completedRetention == null
                || completedRetention.compareTo(Duration.ofDays(1)) < 0
                || completedRetention.compareTo(Duration.ofDays(365)) > 0
                || completedRetention.getNano() != 0
                || cleanupBatchSize < 1
                || cleanupBatchSize > 1000)
            throw new IllegalArgumentException("Revocation retention or batch size is invalid");
    }

    @Override
    public String toString() {
        return "RevocationProperties[redacted]";
    }
}
