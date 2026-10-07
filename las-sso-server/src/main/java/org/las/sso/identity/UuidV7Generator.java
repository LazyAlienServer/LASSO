package org.las.sso.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Generates RFC 9562 UUID version 7 identifiers. */
@Component
public final class UuidV7Generator {

    private static final long TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL;
    private static final long RANDOM_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final long VERSION_7_BITS = 0x7000L;
    private static final long RFC_4122_VARIANT_BITS = 0x8000_0000_0000_0000L;

    private final Clock clock;
    private final SecureRandom secureRandom;

    public UuidV7Generator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    UuidV7Generator(Clock clock, SecureRandom secureRandom) {
        this.clock = Objects.requireNonNull(clock);
        this.secureRandom = Objects.requireNonNull(secureRandom);
    }

    public UUID generate() {
        long unixMillis = clock.millis() & TIMESTAMP_MASK;
        long randomA = secureRandom.nextInt(1 << 12);
        long randomB = secureRandom.nextLong() & RANDOM_B_MASK;

        long mostSignificantBits = (unixMillis << 16) | VERSION_7_BITS | randomA;
        long leastSignificantBits = RFC_4122_VARIANT_BITS | randomB;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
