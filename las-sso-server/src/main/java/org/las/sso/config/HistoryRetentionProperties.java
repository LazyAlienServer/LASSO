package org.las.sso.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("las.sso.history")
public record HistoryRetentionProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("P90D") Duration sessionRetention,
        @DefaultValue("P90D") Duration credentialRetention,
        @DefaultValue("P30D") Duration authorizationRetention,
        @DefaultValue("P30D") Duration refreshHistoryRetention,
        @DefaultValue("P30D") Duration outboxRetention,
        @DefaultValue("PT5M") Duration cleanupInitialDelay,
        @DefaultValue("PT5M") Duration cleanupInterval) {
    public HistoryRetentionProperties {
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("History batch size must be 1 through 1000");
        validate(sessionRetention, 90);
        validate(credentialRetention, 90);
        validate(authorizationRetention, 30);
        validate(refreshHistoryRetention, 30);
        validate(outboxRetention, 30);
        if (cleanupInitialDelay == null
                || cleanupInitialDelay.compareTo(Duration.ofSeconds(1)) < 0
                || cleanupInitialDelay.compareTo(Duration.ofDays(1)) > 0
                || cleanupInterval == null
                || cleanupInterval.compareTo(Duration.ofSeconds(1)) < 0
                || cleanupInterval.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException(
                    "History cleanup delay/interval must be between one second and one day");
    }

    private static void validate(Duration value, int minimumDays) {
        if (value == null
                || value.compareTo(Duration.ofDays(minimumDays)) < 0
                || value.compareTo(Duration.ofDays(3650)) > 0
                || value.getNano() != 0
                || value.toSeconds() % 86400 != 0)
            throw new IllegalArgumentException(
                    "History retention must be whole days within its safety minimum and 3650 days");
    }
}
