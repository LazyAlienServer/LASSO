package org.las.sso.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.config.MonitoringProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class MonitoringPropertiesTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "raw-secret",
                "{noop}secret",
                "{bcrypt}$2a$04$aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void rejectsMissingRawOrWeakCollectorCredential(String value) {
        assertThatThrownBy(() -> new MonitoringProperties(true, value, Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validCredentialIsRedactedFromPropertiesRepresentation() {
        String encoded =
                "{bcrypt}" + new BCryptPasswordEncoder(10).encode("private fixture phrase");
        var properties = new MonitoringProperties(true, encoded, Duration.ofSeconds(15));
        assertThat(properties.toString())
                .doesNotContain(encoded)
                .contains("passwordHash=[redacted]");
    }

    @Test
    void disabledMonitoringNeedsNoCredentialButStillBoundsPolling() {
        new MonitoringProperties(false, "", Duration.ofSeconds(15));
        assertThatThrownBy(() -> new MonitoringProperties(false, "", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
