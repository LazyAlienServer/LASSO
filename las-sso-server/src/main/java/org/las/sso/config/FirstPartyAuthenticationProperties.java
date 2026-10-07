package org.las.sso.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("las.sso.first-party-authentication")
public record FirstPartyAuthenticationProperties(
        @Min(1) @DefaultValue("10") int clientFailures,
        @Min(1) @DefaultValue("20") int networkFailures,
        @DefaultValue("PT15M") Duration window) {

    public FirstPartyAuthenticationProperties {
        if (clientFailures < 1 || networkFailures < 1) {
            throw new IllegalArgumentException(
                    "First-party authentication budgets must be positive");
        }
        if (window == null
                || window.isNegative()
                || window.isZero()
                || window.toSeconds() < 1
                || window.getNano() != 0) {
            throw new IllegalArgumentException(
                    "First-party authentication window must be whole positive seconds");
        }
    }
}
