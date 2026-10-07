package org.las.sso.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("las.sso.monitoring")
public record MonitoringProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String passwordHash,
        @DefaultValue("PT15S") Duration snapshotInterval) {

    public MonitoringProperties {
        if (enabled
                && (passwordHash == null
                        || !passwordHash.matches(
                                "\\{bcrypt}\\$2[aby]\\$(10|11|12|13|14)\\$[./A-Za-z0-9]{53}"))) {
            throw new IllegalArgumentException(
                    "Monitoring requires a dedicated bcrypt credential with cost 10 through 14");
        }
        if (snapshotInterval == null
                || snapshotInterval.compareTo(Duration.ofSeconds(1)) < 0
                || snapshotInterval.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "Monitoring snapshot interval must be between one second and five minutes");
        }
    }

    @Override
    public String toString() {
        return "MonitoringProperties[enabled="
                + enabled
                + ", passwordHash=[redacted], snapshotInterval="
                + snapshotInterval
                + "]";
    }
}
