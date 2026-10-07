package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultLasSsoLogoutReplayStoreTest {

    @Test
    void identicalReplayIsIdempotentButConflictingContentIsRejected() {
        var store = new DefaultLasSsoLogoutReplayStore();
        Instant expiresAt = Instant.now().plusSeconds(120);

        assertThat(store.accept("https://sso.example", "jti-1", "digest-a", expiresAt)).isTrue();
        assertThat(store.accept("https://sso.example", "jti-1", "digest-a", expiresAt)).isTrue();
        assertThat(store.accept("https://sso.example", "jti-1", "digest-b", expiresAt)).isFalse();
        assertThat(store.accept("https://other.example", "jti-1", "digest-b", expiresAt)).isTrue();
    }

    @Test
    void expiredReplayCanBeReplaced() {
        var store = new DefaultLasSsoLogoutReplayStore();
        assertThat(
                        store.accept(
                                "https://sso.example",
                                "jti-expired",
                                "digest-old",
                                Instant.now().minusSeconds(1)))
                .isTrue();
        assertThat(
                        store.accept(
                                "https://sso.example",
                                "jti-expired",
                                "digest-new",
                                Instant.now().plusSeconds(120)))
                .isTrue();
    }

    @Test
    void failedActionPreservesDigestAndAllowsRetryUntilSuccessfulReceipt() {
        var store = new DefaultLasSsoLogoutReplayStore();
        Instant retainUntil = Instant.now().plusSeconds(180);
        var calls = new AtomicInteger();

        assertThatThrownBy(
                        () ->
                                store.process(
                                        "issuer",
                                        "jti",
                                        "digest",
                                        retainUntil,
                                        () -> {
                                            calls.incrementAndGet();
                                            throw new IllegalStateException(
                                                    "Fixture execution failed");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.process("issuer", "jti", "different", retainUntil, calls::incrementAndGet))
                .isEqualTo(LasSsoLogoutReplayStore.Result.CONFLICT);
        assertThat(store.process("issuer", "jti", "digest", retainUntil, calls::incrementAndGet))
                .isEqualTo(LasSsoLogoutReplayStore.Result.SUCCESS);
        assertThat(store.process("issuer", "jti", "digest", retainUntil, calls::incrementAndGet))
                .isEqualTo(LasSsoLogoutReplayStore.Result.SUCCESS);
        assertThat(calls).hasValue(2);
    }

    @Test
    void legacyDigestAcceptanceIsNotAnExecutionReceipt() {
        var store = new DefaultLasSsoLogoutReplayStore();
        var calls = new AtomicInteger();
        Instant expiry = Instant.now().plusSeconds(180);
        assertThat(store.accept("issuer", "jti", "digest", expiry)).isTrue();
        assertThat(store.process("issuer", "jti", "digest", expiry, calls::incrementAndGet))
                .isEqualTo(LasSsoLogoutReplayStore.Result.SUCCESS);
        assertThat(calls).hasValue(1);
    }

    @Test
    void expiredReceiptCanBeReplacedButRunningExecutionCannotBeEvicted() throws Exception {
        Instant start = Instant.parse("2026-10-04T00:00:00Z");
        var now = new AtomicReference<>(start);
        Clock clock =
                new Clock() {
                    @Override
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return now.get();
                    }
                };
        var store = new DefaultLasSsoLogoutReplayStore(clock);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () ->
                                    store.process(
                                            "issuer",
                                            "jti",
                                            "digest",
                                            start.plusSeconds(1),
                                            () -> {
                                                calls.incrementAndGet();
                                                entered.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new AssertionError(
                                                                "Fixture release timed out");
                                                } catch (InterruptedException exception) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(
                                                            "Fixture interrupted");
                                                }
                                            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                now.set(start.plusSeconds(2));
                assertThat(
                                store.process(
                                        "issuer",
                                        "jti",
                                        "digest",
                                        start.plusSeconds(3),
                                        calls::incrementAndGet))
                        .isEqualTo(LasSsoLogoutReplayStore.Result.IN_PROGRESS);
                assertThat(
                                store.process(
                                        "issuer",
                                        "jti",
                                        "other",
                                        start.plusSeconds(3),
                                        calls::incrementAndGet))
                        .isEqualTo(LasSsoLogoutReplayStore.Result.CONFLICT);
                // Other token IDs must not wait for this action.
                assertThat(
                                store.process(
                                        "issuer",
                                        "other-jti",
                                        "other",
                                        start.plusSeconds(3),
                                        () -> {}))
                        .isEqualTo(LasSsoLogoutReplayStore.Result.SUCCESS);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS))
                    .isEqualTo(LasSsoLogoutReplayStore.Result.SUCCESS);
            assertThat(
                            store.process(
                                    "issuer",
                                    "jti",
                                    "new",
                                    start.plusSeconds(4),
                                    calls::incrementAndGet))
                    .isEqualTo(LasSsoLogoutReplayStore.Result.SUCCESS);
            assertThat(calls).hasValue(2);
        }
    }
}
