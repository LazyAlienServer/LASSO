package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class LasSsoBackChannelSessionRegistryTest {

    @Test
    void noMatchLogoutRejectsLateRegistrationButNotAnotherSidForTheSameSubject() {
        var registry = new LasSsoBackChannelSessionRegistry();
        assertThat(registry.invalidate("revoked", "subject")).isZero();
        var late = new MockHttpSession();
        assertThatThrownBy(() -> registry.register("revoked", "subject", late))
                .isInstanceOf(IllegalStateException.class);
        var other = new MockHttpSession();
        registry.register("fresh", "subject", other);
        assertThat(registry.invalidate("revoked", "subject")).isZero();
        assertThat(other.isInvalid()).isFalse();
        assertThat(registry.invalidate("fresh", "subject")).isEqualTo(1);
    }

    @Test
    void unregisterDoesNotEraseRevocationAndRepeatRegistrationCannotReviveSid() {
        var registry = new LasSsoBackChannelSessionRegistry();
        var original = new MockHttpSession();
        registry.register("revoked", "subject", original);
        registry.register("revoked", "subject", original);
        assertThat(registry.invalidate("revoked", "subject")).isEqualTo(1);
        registry.unregister(original.getId());
        assertThatThrownBy(() -> registry.register("revoked", "subject", new MockHttpSession()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedInvalidationKeepsMarkerAndLiveIndexForRetry() {
        var registry = new LasSsoBackChannelSessionRegistry();
        var session =
                new MockHttpSession() {
                    boolean fail = true;

                    @Override
                    public void invalidate() {
                        if (fail) {
                            fail = false;
                            throw new IllegalStateException("fixture transient failure");
                        }
                        super.invalidate();
                    }
                };
        registry.register("revoked", "subject", session);
        assertThatThrownBy(() -> registry.invalidate("revoked", "subject"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(session.isInvalid()).isFalse();
        assertThatThrownBy(() -> registry.register("revoked", "subject", new MockHttpSession()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.invalidate("revoked", "subject")).isEqualTo(1);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void configuredRetentionRejectsUntilExactExpiryWithoutExtendingOnRegistrationAttempts() {
        var clock = new MutableClock();
        var registry = new LasSsoBackChannelSessionRegistry(Duration.ofMinutes(5), clock);
        registry.invalidate("revoked", "subject");
        clock.advance(Duration.ofMinutes(5).minusNanos(1));
        assertThatThrownBy(() -> registry.register("revoked", "subject", new MockHttpSession()))
                .isInstanceOf(IllegalStateException.class);
        clock.advance(Duration.ofNanos(1));
        registry.register("revoked", "subject", new MockHttpSession());
        assertThat(registry.invalidate("revoked", "subject")).isEqualTo(1);
    }

    @Test
    void subsequentLogoutExtendsMarkerRatherThanShorteningIt() {
        var clock = new MutableClock();
        var registry = new LasSsoBackChannelSessionRegistry(Duration.ofMinutes(5), clock);
        registry.invalidate("revoked", "subject");
        clock.advance(Duration.ofMinutes(4));
        registry.invalidate("revoked", "subject");
        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> registry.register("revoked", "subject", new MockHttpSession()))
                .isInstanceOf(IllegalStateException.class);
        clock.advance(Duration.ofMinutes(4));
        registry.register("revoked", "subject", new MockHttpSession());
    }

    @Test
    void fourteenDayRetentionIncludesTheLastInstantBeforeExpiry() {
        var clock = new MutableClock();
        var registry = new LasSsoBackChannelSessionRegistry(Duration.ofDays(14), clock);
        registry.invalidate("revoked", "subject");
        clock.advance(Duration.ofDays(14).minusNanos(1));
        assertThatThrownBy(() -> registry.register("revoked", "subject", new MockHttpSession()))
                .isInstanceOf(IllegalStateException.class);
        clock.advance(Duration.ofNanos(1));
        registry.register("revoked", "subject", new MockHttpSession());
    }

    @Test
    void registrationAlreadyStartedButNotIndexedCannotPassACompletedLogout() throws Exception {
        var registry = new LasSsoBackChannelSessionRegistry();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var late =
                new MockHttpSession() {
                    @Override
                    public String getId() {
                        entered.countDown();
                        await(release);
                        return super.getId();
                    }
                };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var registration =
                    executor.submit(
                            () -> {
                                try {
                                    registry.register("revoked", "subject", late);
                                    return false;
                                } catch (IllegalStateException rejected) {
                                    return true;
                                }
                            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(registry.invalidate("revoked", "subject")).isZero();
            } finally {
                release.countDown();
            }
            assertThat(registration.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void registrationWinsThenLogoutInvalidatesOutsideLockAndBlocksNewSameSidRegistration()
            throws Exception {
        var registry = new LasSsoBackChannelSessionRegistry();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var existing =
                new MockHttpSession() {
                    @Override
                    public void invalidate() {
                        entered.countDown();
                        await(release);
                        super.invalidate();
                        // Container destruction listeners may reenter registry cleanup.
                        registry.unregister(getId());
                    }
                };
        registry.register("revoked", "subject", existing);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var logout = executor.submit(() -> registry.invalidate("revoked", "subject"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var later =
                        executor.submit(
                                () -> {
                                    assertThatThrownBy(
                                                    () ->
                                                            registry.register(
                                                                    "revoked",
                                                                    "subject",
                                                                    new MockHttpSession()))
                                            .isInstanceOf(IllegalStateException.class);
                                    var other = new MockHttpSession();
                                    registry.register("other", "subject", other);
                                    return registry.invalidate("other", "subject");
                                });
                assertThat(later.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(existing.isInvalid()).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(logout.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(existing.isInvalid()).isTrue();
        }
    }

    @Test
    void directLegacySubjectInvalidationDoesNotPermanentlyBanTheHumanPrincipal() {
        var registry = new LasSsoBackChannelSessionRegistry();
        var before = new MockHttpSession();
        registry.register("before", "subject", before);
        assertThat(registry.invalidate(null, "subject")).isEqualTo(1);
        var after = new MockHttpSession();
        registry.register("after", "subject", after);
        assertThat(after.isInvalid()).isFalse();
    }

    @Test
    void retentionRejectsUnsupportedValues() {
        for (Duration invalid :
                new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(15)}) {
            assertThatThrownBy(() -> new LasSsoBackChannelSessionRegistry(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new LasSsoBackChannelSessionRegistry(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Fixture latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture interrupted", exception);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-10-05T00:00:00Z"));

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
