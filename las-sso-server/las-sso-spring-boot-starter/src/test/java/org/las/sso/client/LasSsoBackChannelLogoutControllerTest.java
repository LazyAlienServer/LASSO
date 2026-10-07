package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@ExtendWith(OutputCaptureExtension.class)
class LasSsoBackChannelLogoutControllerTest {

    private static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";

    @Test
    void validLogoutTokenInvalidatesRegisteredSessionAndAllowsIdenticalReplay() {
        var registry = new LasSsoBackChannelSessionRegistry();
        var session = new MockHttpSession();
        registry.register("sid-1", "subject-1", session);
        Jwt jwt = logoutToken("token-1", "jti-1", "client-1");
        JwtDecoder decoder = token -> jwt;
        var controller = new LasSsoBackChannelLogoutController(decoder, "client-1", registry);

        assertThat(controller.logout("token-1").getStatusCode().value()).isEqualTo(204);
        assertThat(session.isInvalid()).isTrue();
        assertThat(controller.logout("token-1").getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void sidLogoutDoesNotInvalidateAnotherSessionForTheSameSubject() {
        var registry = new LasSsoBackChannelSessionRegistry();
        var targetedSession = new MockHttpSession();
        var otherSession = new MockHttpSession();
        registry.register("sid-1", "subject-1", targetedSession);
        registry.register("sid-2", "subject-1", otherSession);
        var controller =
                new LasSsoBackChannelLogoutController(
                        token -> logoutToken(token, "jti-sid", "client-1"), "client-1", registry);

        assertThat(controller.logout("token-sid").getStatusCode().value()).isEqualTo(204);
        assertThat(targetedSession.isInvalid()).isTrue();
        assertThat(otherSession.isInvalid()).isFalse();
    }

    @Test
    void unregisteredSessionIsNotRetainedForLaterSubjectLogout() {
        var registry = new LasSsoBackChannelSessionRegistry();
        var destroyedSession = new MockHttpSession();
        registry.register("sid-destroyed", "subject-1", destroyedSession);

        registry.unregister(destroyedSession.getId());

        assertThat(registry.invalidate(null, "subject-1")).isZero();
        assertThat(destroyedSession.isInvalid()).isFalse();
    }

    @Test
    void sameJtiWithDifferentTokenAndWrongAudienceAreRejected() {
        var registry = new LasSsoBackChannelSessionRegistry();
        Jwt valid = logoutToken("token-1", "jti-1", "client-1");
        JwtDecoder decoder =
                token -> "token-1".equals(token) ? valid : logoutToken(token, "jti-1", "client-1");
        var controller = new LasSsoBackChannelLogoutController(decoder, "client-1", registry);

        assertThat(controller.logout("token-1").getStatusCode().value()).isEqualTo(204);
        assertThat(controller.logout("different-token").getStatusCode().value()).isEqualTo(400);

        var wrongAudience =
                new LasSsoBackChannelLogoutController(
                        token -> logoutToken(token, "jti-2", "other-client"), "client-1", registry);
        assertThat(wrongAudience.logout("token-2").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void customReplayAndSessionStoresAreUsedByTheReceiver() {
        var session = new MockHttpSession();
        var replayCalls = new AtomicReference<String>();
        var sessionCalls = new AtomicReference<String>();
        LasSsoLogoutReplayStore replay =
                new LasSsoLogoutReplayStore() {
                    final DefaultLasSsoLogoutReplayStore delegate =
                            new DefaultLasSsoLogoutReplayStore();

                    @Override
                    public boolean accept(
                            String issuer, String tokenId, String digest, Instant expiresAt) {
                        throw new AssertionError(
                                "Receiver must use execution receipts, not the legacy accept hook");
                    }

                    @Override
                    public Result process(
                            String issuer,
                            String tokenId,
                            String digest,
                            Instant retainUntil,
                            Runnable action) {
                        replayCalls.set(issuer + ":" + tokenId + ":" + digest);
                        return delegate.process(issuer, tokenId, digest, retainUntil, action);
                    }
                };
        LasSsoBackChannelSessionStore sessions =
                new LasSsoBackChannelSessionStore() {
                    @Override
                    public void register(
                            String sid, String subject, jakarta.servlet.http.HttpSession value) {}

                    @Override
                    public int invalidate(String sid, String subject) {
                        sessionCalls.set(sid + ":" + subject);
                        session.invalidate();
                        return 1;
                    }

                    @Override
                    public void unregister(String sessionId) {}
                };
        var controller =
                new LasSsoBackChannelLogoutController(
                        token -> logoutToken(token, "jti-custom", "client-1"),
                        "client-1",
                        sessions,
                        replay);

        assertThat(controller.logout("custom-token").getStatusCode().value()).isEqualTo(204);
        assertThat(replayCalls.get()).startsWith("http://issuer.example:jti-custom:");
        assertThat(sessionCalls.get()).isEqualTo("sid-1:subject-1");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void sessionExecutionFailureIsRetryableAndDoesNotBecomeBadTokenOrSuccess(
            CapturedOutput output) {
        var calls = new AtomicInteger();
        var session = new MockHttpSession();
        var store =
                new LasSsoBackChannelSessionStore() {
                    @Override
                    public void register(
                            String sid, String subject, jakarta.servlet.http.HttpSession value) {}

                    @Override
                    public void unregister(String id) {}

                    @Override
                    public int invalidate(String sid, String subject) {
                        if (calls.incrementAndGet() == 1)
                            throw new IllegalArgumentException("adapter-secret-canary");
                        session.invalidate();
                        return 1;
                    }
                };
        var receiver =
                new LasSsoBackChannelLogoutController(
                        token -> logoutToken(token, "jti-retry", "client-1"), "client-1", store);

        assertThat(receiver.logout("retry-token").getStatusCode().value()).isEqualTo(503);
        assertThat(session.isInvalid()).isFalse();
        assertThat(receiver.logout("retry-token").getStatusCode().value()).isEqualTo(204);
        assertThat(receiver.logout("retry-token").getStatusCode().value()).isEqualTo(204);
        assertThat(session.isInvalid()).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(output.getAll())
                .contains("failureType=IllegalArgumentException")
                .doesNotContain("adapter-secret-canary");
    }

    @Test
    void registryRetainsAStillLiveSessionWhenItsInvalidationFails() {
        var registry = new LasSsoBackChannelSessionRegistry();
        var attempts = new AtomicInteger();
        var session =
                new MockHttpSession() {
                    @Override
                    public void invalidate() {
                        if (attempts.incrementAndGet() == 1)
                            throw new IllegalStateException("fixture transient failure");
                        super.invalidate();
                    }
                };
        registry.register("sid-1", "subject-1", session);
        var receiver =
                new LasSsoBackChannelLogoutController(
                        token -> logoutToken(token, "jti-registry-retry", "client-1"),
                        "client-1",
                        registry);

        assertThat(receiver.logout("registry-retry-token").getStatusCode().value()).isEqualTo(503);
        assertThat(session.isInvalid()).isFalse();
        assertThat(receiver.logout("registry-retry-token").getStatusCode().value()).isEqualTo(204);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void partialSessionFailureRetriesOnlyRemainingLiveSessionsBeforeCachingSuccess() {
        var registry = new LasSsoBackChannelSessionRegistry();
        var firstAttempts = new AtomicInteger();
        var failedAttempts = new AtomicInteger();
        var first =
                new MockHttpSession() {
                    @Override
                    public void invalidate() {
                        firstAttempts.incrementAndGet();
                        super.invalidate();
                    }
                };
        var failed =
                new MockHttpSession() {
                    @Override
                    public void invalidate() {
                        if (failedAttempts.incrementAndGet() == 1)
                            throw new IllegalStateException("fixture partial failure");
                        super.invalidate();
                    }
                };
        registry.register("sid-1", "subject-1", first);
        registry.register("sid-1", "subject-1", failed);
        var receiver =
                new LasSsoBackChannelLogoutController(
                        token -> logoutToken(token, "jti-partial", "client-1"),
                        "client-1",
                        registry);

        assertThat(receiver.logout("partial-token").getStatusCode().value()).isEqualTo(503);
        assertThat(first.isInvalid()).isTrue();
        assertThat(failed.isInvalid()).isFalse();
        assertThat(receiver.logout("partial-token").getStatusCode().value()).isEqualTo(204);
        assertThat(receiver.logout("partial-token").getStatusCode().value()).isEqualTo(204);
        assertThat(failed.isInvalid()).isTrue();
        assertThat(firstAttempts).hasValue(1);
        assertThat(failedAttempts).hasValue(2);
    }

    @Test
    void acceptOnlyCustomAdapterRemainsCompatibleButReceiverFailsClosed() {
        var calls = new AtomicInteger();
        LasSsoLogoutReplayStore legacy =
                (issuer, id, digest, expires) -> {
                    throw new AssertionError("Legacy accept cannot claim execution success");
                };
        var receiver =
                new LasSsoBackChannelLogoutController(
                        token -> logoutToken(token, "jti-legacy", "client-1"),
                        "client-1",
                        countingStore(calls),
                        legacy);

        assertThat(receiver.logout("legacy-token").getStatusCode().value()).isEqualTo(503);
        assertThat(calls).hasValue(0);
    }

    @Test
    void storageFailureIsRetryableWithoutExecutingSessionsAndDoesNotLeakDetails(
            CapturedOutput output) {
        var calls = new AtomicInteger();
        var replay =
                new LasSsoLogoutReplayStore() {
                    @Override
                    public boolean accept(
                            String issuer, String id, String digest, Instant expires) {
                        return true;
                    }

                    @Override
                    public Result process(
                            String issuer,
                            String id,
                            String digest,
                            Instant until,
                            Runnable action) {
                        throw new IllegalArgumentException("replay-storage-secret-canary");
                    }
                };
        var receiver =
                new LasSsoBackChannelLogoutController(
                        token -> logoutToken(token, "jti-storage", "client-1"),
                        "client-1",
                        countingStore(calls),
                        replay);

        var result = receiver.logout("storage-token");

        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(calls).hasValue(0);
        assertThat(output.getAll()).doesNotContain("replay-storage-secret-canary");
    }

    @Test
    void concurrentIdenticalReplayIsRetryableUntilFirstExecutionSucceeds() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var store =
                new LasSsoBackChannelSessionStore() {
                    @Override
                    public void register(
                            String sid, String sub, jakarta.servlet.http.HttpSession session) {}

                    @Override
                    public void unregister(String id) {}

                    @Override
                    public int invalidate(String sid, String sub) {
                        calls.incrementAndGet();
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("Fixture release timed out");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Fixture interrupted");
                        }
                        return 1;
                    }
                };
        // One stable JWT per input; different raw content with the same jti is a conflict.
        Jwt jwt = logoutToken("same-token", "jti-concurrent", "client-1");
        var receiver = new LasSsoBackChannelLogoutController(token -> jwt, "client-1", store);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(() -> receiver.logout("same-token").getStatusCode().value());
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(receiver.logout("same-token").getStatusCode().value()).isEqualTo(503);
                assertThat(receiver.logout("conflicting-token").getStatusCode().value())
                        .isEqualTo(400);
                assertThat(calls).hasValue(1);
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(receiver.logout("same-token").getStatusCode().value()).isEqualTo(204);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void receiverRetainsReceiptThroughExpirySkewAndRejectsExactExpiryBoundary() {
        Instant issued = Instant.parse("2026-10-04T00:00:00Z");
        var now = new java.util.concurrent.atomic.AtomicReference<>(issued);
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
        Jwt jwt =
                Jwt.withTokenValue("clock-token")
                        .header("alg", "RS256")
                        .header("typ", "logout+jwt")
                        .issuer("http://issuer.example")
                        .audience(List.of("client-1"))
                        .issuedAt(issued)
                        .expiresAt(issued.plusSeconds(120))
                        .claim("jti", "clock-jti")
                        .claim("sid", "clock-sid")
                        .claim("events", Map.of(EVENT, Map.of()))
                        .build();
        var calls = new AtomicInteger();
        var receiver =
                new LasSsoBackChannelLogoutController(
                        token -> jwt,
                        "client-1",
                        countingStore(calls),
                        new DefaultLasSsoLogoutReplayStore(clock),
                        "http://issuer.example",
                        clock);

        assertThat(receiver.logout("clock-token").getStatusCode().value()).isEqualTo(204);
        now.set(issued.plusSeconds(179));
        assertThat(receiver.logout("clock-token").getStatusCode().value()).isEqualTo(204);
        assertThat(receiver.logout("conflict-after-exp").getStatusCode().value()).isEqualTo(400);
        assertThat(calls).hasValue(1);
        now.set(issued.plusSeconds(180));
        assertThat(receiver.logout("clock-token").getStatusCode().value()).isEqualTo(400);
        assertThat(calls).hasValue(1);
    }

    @Test
    void decoderInfrastructureFailureReturnsNoStore503WithoutLeakingDetails(CapturedOutput output) {
        var calls = new AtomicInteger();
        var receiver =
                new LasSsoBackChannelLogoutController(
                        token -> {
                            throw new org.springframework.security.oauth2.jwt.JwtException(
                                    "decoder-secret-canary");
                        },
                        "client-1",
                        countingStore(calls));

        var result = receiver.logout("opaque-secret-token");
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(calls).hasValue(0);
        assertThat(output.getAll()).doesNotContain("decoder-secret-canary", "opaque-secret-token");
    }

    @Test
    void conflictingContentStaysRejectedAfterFailedExecutionThenOriginalContentMayRetry() {
        var calls = new AtomicInteger();
        var store =
                new LasSsoBackChannelSessionStore() {
                    @Override
                    public void register(
                            String sid, String sub, jakarta.servlet.http.HttpSession session) {}

                    @Override
                    public void unregister(String id) {}

                    @Override
                    public int invalidate(String sid, String sub) {
                        if (calls.incrementAndGet() == 1)
                            throw new IllegalStateException("Fixture failed");
                        return 1;
                    }
                };
        Jwt jwt = logoutToken("original", "jti-failed-conflict", "client-1");
        var receiver = new LasSsoBackChannelLogoutController(token -> jwt, "client-1", store);

        assertThat(receiver.logout("original").getStatusCode().value()).isEqualTo(503);
        assertThat(receiver.logout("conflict").getStatusCode().value()).isEqualTo(400);
        assertThat(calls).hasValue(1);
        assertThat(receiver.logout("original").getStatusCode().value()).isEqualTo(204);
        assertThat(calls).hasValue(2);
    }

    @Test
    void receiverStillChecksFixedIssuerWhenCustomDecoderReturnsAnOtherwiseValidJwt() {
        var calls = new AtomicInteger();
        var receiver =
                new LasSsoBackChannelLogoutController(
                        token -> logoutToken(token, "jti-custom-issuer", "client-1"),
                        "client-1",
                        countingStore(calls),
                        new DefaultLasSsoLogoutReplayStore(),
                        "https://expected-issuer.example",
                        Clock.systemUTC());

        assertThat(receiver.logout("wrong-issuer-token").getStatusCode().value()).isEqualTo(400);
        assertThat(calls).hasValue(0);
    }

    private static LasSsoBackChannelSessionStore countingStore(AtomicInteger calls) {
        return new LasSsoBackChannelSessionStore() {
            @Override
            public void register(
                    String sid, String sub, jakarta.servlet.http.HttpSession session) {}

            @Override
            public void unregister(String id) {}

            @Override
            public int invalidate(String sid, String sub) {
                return calls.incrementAndGet();
            }
        };
    }

    private static Jwt logoutToken(String token, String jti, String audience) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .header("typ", "logout+jwt")
                .issuer("http://issuer.example")
                .subject("subject-1")
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(120))
                .claim("jti", jti)
                .claim("sid", "sid-1")
                .claim("events", Map.of(EVENT, Map.of()))
                .build();
    }
}
