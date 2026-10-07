package org.las.sso.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local replay store for the default single-JVM starter behavior. */
public final class DefaultLasSsoLogoutReplayStore implements LasSsoLogoutReplayStore {

    private final ConcurrentHashMap<String, Replay> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public DefaultLasSsoLogoutReplayStore() {
        this(Clock.systemUTC());
    }

    DefaultLasSsoLogoutReplayStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean accept(String issuer, String tokenId, String contentDigest, Instant expiresAt) {
        Instant now = clock.instant();
        cleanup(now);
        String key = issuer + "\n" + tokenId;
        var result = new AtomicReference<Boolean>();
        entries.compute(
                key,
                (ignored, existing) -> {
                    Replay entry =
                            existing == null
                                            || (!existing.running
                                                    && !existing.retainUntil.isAfter(now))
                                    ? new Replay(contentDigest, expiresAt)
                                    : existing;
                    result.set(sameDigest(entry, contentDigest));
                    return entry;
                });
        return result.get();
    }

    @Override
    public Result process(
            String issuer,
            String tokenId,
            String contentDigest,
            Instant retainUntil,
            Runnable action) {
        Instant now = clock.instant();
        cleanup(now);
        String key = issuer + "\n" + tokenId;
        var result = new AtomicReference<Result>();
        var owned = new AtomicReference<Replay>();
        entries.compute(
                key,
                (ignored, existing) -> {
                    Replay entry =
                            existing == null
                                            || (!existing.running
                                                    && !existing.retainUntil.isAfter(now))
                                    ? new Replay(contentDigest, retainUntil)
                                    : existing;
                    if (!sameDigest(entry, contentDigest)) result.set(Result.CONFLICT);
                    else if (entry.completed) result.set(Result.SUCCESS);
                    else if (entry.running) result.set(Result.IN_PROGRESS);
                    else {
                        if (retainUntil.isAfter(entry.retainUntil)) entry.retainUntil = retainUntil;
                        entry.running = true;
                        owned.set(entry);
                    }
                    return entry;
                });
        if (owned.get() == null) return result.get();
        boolean completed = false;
        try {
            action.run();
            completed = true;
            return Result.SUCCESS;
        } finally {
            boolean success = completed;
            entries.computeIfPresent(
                    key,
                    (ignored, entry) -> {
                        if (entry == owned.get()) {
                            entry.completed = success;
                            entry.running = false;
                        }
                        return entry;
                    });
        }
    }

    private void cleanup(Instant now) {
        for (String key : entries.keySet()) {
            entries.computeIfPresent(
                    key,
                    (ignored, entry) ->
                            !entry.running && !entry.retainUntil.isAfter(now) ? null : entry);
        }
    }

    private static boolean sameDigest(Replay entry, String digest) {
        return MessageDigest.isEqual(
                entry.contentDigest.getBytes(StandardCharsets.US_ASCII),
                digest.getBytes(StandardCharsets.US_ASCII));
    }

    public static String sha256Base64Url(String token) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Mutable state is accessed only inside the map's per-key compute boundary. */
    private static final class Replay {
        final String contentDigest;
        Instant retainUntil;
        boolean running;
        boolean completed;

        Replay(String contentDigest, Instant retainUntil) {
            this.contentDigest = contentDigest;
            this.retainUntil = retainUntil;
        }
    }
}
