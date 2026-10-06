package org.las.sso.client;

import java.util.Set;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpSession;

public final class LasSsoBackChannelSessionRegistry implements LasSsoBackChannelSessionStore {

    private final Object stateLock = new Object();
    private final Map<String, Instant> revokedSids = new HashMap<>();
    private final Duration revocationRetention;
    private final Clock clock;
    private Instant nextCleanup = Instant.MIN;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, HttpSession>> bySid =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, HttpSession>> bySubject =
            new ConcurrentHashMap<>();

    public LasSsoBackChannelSessionRegistry() {
        this(Duration.ofDays(14));
    }

    public LasSsoBackChannelSessionRegistry(Duration revocationRetention) {
        this(revocationRetention, Clock.systemUTC());
    }

    LasSsoBackChannelSessionRegistry(Duration revocationRetention, Clock clock) {
        if (revocationRetention == null || revocationRetention.isNegative() || revocationRetention.isZero()
                || revocationRetention.compareTo(Duration.ofDays(14)) > 0) {
            throw new IllegalArgumentException("Revocation retention must be between 0 and 14 days");
        }
        this.revocationRetention = revocationRetention;
        this.clock = java.util.Objects.requireNonNull(clock);
    }
    @Override
    public void register(String sid, String subject, HttpSession session) {
        // Never call session/lifecycle code while holding the registry state lock.
        String sessionId = session.getId();
        synchronized (stateLock) {
            Instant now = clock.instant();
            cleanupRevocations(now);
            if (sid != null && !sid.isBlank()) {
                Instant revokedUntil = revokedSids.get(sid);
                if (revokedUntil != null && revokedUntil.isAfter(now)) {
                    throw new IllegalStateException("SSO session was revoked before local registration");
                }
                revokedSids.remove(sid);
                bySid.computeIfAbsent(sid, ignored -> new ConcurrentHashMap<>()).put(sessionId, session);
            }
            if (subject != null && !subject.isBlank()) {
                bySubject.computeIfAbsent(subject, ignored -> new ConcurrentHashMap<>()).put(sessionId, session);
            }
        }
    }

    @Override
    public int invalidate(String sid, String subject) {
        var sessions = new ConcurrentHashMap<String, HttpSession>();
        synchronized (stateLock) {
            Instant now = clock.instant();
            cleanupRevocations(now);
            if (sid != null && !sid.isBlank()) {
                // Record even no-match and failed invalidations: late callbacks must not revive this sid.
                revokedSids.merge(sid, now.plus(revocationRetention),
                        (previous, candidate) -> previous.isAfter(candidate) ? previous : candidate);
                collect(sessions, bySid.get(sid));
            } else if (subject != null && !subject.isBlank()) {
                // Legacy direct subject invalidation only targets the current snapshot; wire Logout requires sid.
                collect(sessions, bySubject.get(subject));
            }
        }
        int invalidated = 0;
        RuntimeException failure = null;
        for (HttpSession session : sessions.values()) {
            try {
                String id = session.getId();
                session.invalidate();
                removeSession(id);
                invalidated++;
            } catch (RuntimeException exception) {
                boolean alreadyInvalid = false;
                try {
                    session.getCreationTime();
                } catch (IllegalStateException ended) {
                    alreadyInvalid = true;
                }
                if (alreadyInvalid) removeSession(session.getId());
                else failure = exception; // Keep the live session indexed for a subsequent retry.
            }
        }
        if (failure != null) throw new IllegalStateException("Local session invalidation incomplete");
        return invalidated;
    }

    @Override
    public void unregister(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        synchronized (stateLock) {
            for (var entry : Set.copyOf(bySid.entrySet())) {
                entry.getValue().remove(sessionId);
                if (entry.getValue().isEmpty()) bySid.remove(entry.getKey(), entry.getValue());
            }
            for (var entry : Set.copyOf(bySubject.entrySet())) {
                entry.getValue().remove(sessionId);
                if (entry.getValue().isEmpty()) bySubject.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private void cleanupRevocations(Instant now) {
        if (!now.isBefore(nextCleanup)) {
            revokedSids.values().removeIf(until -> !until.isAfter(now));
            nextCleanup = now.plusSeconds(60);
        }
    }

    private void removeSession(String sessionId) {
        unregister(sessionId);
    }

    private static void collect(
            ConcurrentHashMap<String, HttpSession> target,
            ConcurrentHashMap<String, HttpSession> source) {
        if (source != null) target.putAll(source);
    }
}
