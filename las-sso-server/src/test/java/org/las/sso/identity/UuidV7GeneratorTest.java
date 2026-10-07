package org.las.sso.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    @Test
    void generatesRfc9562VersionSevenUuidWithEmbeddedUnixMilliseconds() {
        Instant instant = Instant.parse("2026-08-02T00:00:00Z");
        var generator =
                new UuidV7Generator(
                        Clock.fixed(instant, ZoneOffset.UTC),
                        new SecureRandom(new byte[] {1, 2, 3, 4}));

        var id = generator.generate();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(instant.toEpochMilli());
    }
}
