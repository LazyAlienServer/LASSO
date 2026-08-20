package org.las.sso.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpSession;

public final class LasSsoBackChannelSessionRegistry {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, HttpSession>> bySid =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, HttpSession>> bySubject =
            new ConcurrentHashMap<>();

    public void register(String sid, String subject, HttpSession session) {
        if (sid != null && !sid.isBlank()) {
            bySid.computeIfAbsent(sid, ignored -> new ConcurrentHashMap<>())
                    .put(session.getId(), session);
        }
        if (subject != null && !subject.isBlank()) {
            bySubject.computeIfAbsent(subject, ignored -> new ConcurrentHashMap<>())
                    .put(session.getId(), session);
        }
    }

    public int invalidate(String sid, String subject) {
        var sessions = new ConcurrentHashMap<String, HttpSession>();
        if (sid != null && !sid.isBlank()) {
            collect(sessions, bySid.remove(sid));
        } else if (subject != null && !subject.isBlank()) {
            collect(sessions, bySubject.remove(subject));
        }
        int invalidated = 0;
        for (HttpSession session : sessions.values()) {
            try {
                String id = session.getId();
                session.invalidate();
                removeSession(id);
                invalidated++;
            } catch (IllegalStateException ignored) {
                // Already invalidated is an idempotent success.
            }
        }
        return invalidated;
    }

    public void unregister(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        for (var entry : Set.copyOf(bySid.entrySet())) {
            entry.getValue().remove(sessionId);
            if (entry.getValue().isEmpty()) bySid.remove(entry.getKey(), entry.getValue());
        }
        for (var entry : Set.copyOf(bySubject.entrySet())) {
            entry.getValue().remove(sessionId);
            if (entry.getValue().isEmpty()) bySubject.remove(entry.getKey(), entry.getValue());
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
