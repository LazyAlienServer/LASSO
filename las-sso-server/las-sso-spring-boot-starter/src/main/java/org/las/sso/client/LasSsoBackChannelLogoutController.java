package org.las.sso.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class LasSsoBackChannelLogoutController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LasSsoBackChannelLogoutController.class);

    private static final String LOGOUT_EVENT =
            "http://schemas.openid.net/event/backchannel-logout";

    private final JwtDecoder jwtDecoder;
    private final String clientId;
    private final LasSsoBackChannelSessionRegistry sessions;
    private final ConcurrentHashMap<String, Replay> replayCache = new ConcurrentHashMap<>();

    LasSsoBackChannelLogoutController(
            @Qualifier("lasSsoLogoutTokenDecoder") JwtDecoder jwtDecoder,
            String clientId,
            LasSsoBackChannelSessionRegistry sessions) {
        this.jwtDecoder = jwtDecoder;
        this.clientId = clientId;
        this.sessions = sessions;
    }

    @PostMapping(path = "/internal/oidc/backchannel-logout",
            consumes = "application/x-www-form-urlencoded")
    ResponseEntity<Void> logout(@RequestParam("logout_token") String logoutToken) {
        try {
            Jwt jwt = jwtDecoder.decode(logoutToken);
            validate(jwt);
            if (!acceptReplay(jwt, logoutToken)) {
                return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
            }
            sessions.invalidate(jwt.getClaimAsString("sid"), jwt.getSubject());
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } catch (JwtException | IllegalArgumentException exception) {
            LOGGER.warn("Rejected OIDC Back-Channel Logout Token: {}", exception.getMessage());
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
    }

    private void validate(Jwt jwt) {
        if (!"logout+jwt".equals(jwt.getHeaders().get("typ"))
                || jwt.getId() == null
                || jwt.getExpiresAt() == null
                || jwt.getAudience() == null
                || !jwt.getAudience().contains(clientId)
                || jwt.hasClaim("nonce")) {
            throw new IllegalArgumentException("Invalid logout token envelope");
        }
        Map<String, Object> events = jwt.getClaim("events");
        if (events == null || !events.containsKey(LOGOUT_EVENT)) {
            throw new IllegalArgumentException("Missing logout event");
        }
        String sid = jwt.getClaimAsString("sid");
        if ((sid == null || sid.isBlank())
                && (jwt.getSubject() == null || jwt.getSubject().isBlank())) {
            throw new IllegalArgumentException("Logout token requires sid or sub");
        }
    }

    private boolean acceptReplay(Jwt jwt, String token) {
        Instant now = Instant.now();
        replayCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        Replay candidate = new Replay(digest(token), jwt.getExpiresAt());
        Replay existing = replayCache.putIfAbsent(jwt.getId(), candidate);
        return existing == null || MessageDigest.isEqual(
                existing.digest().getBytes(StandardCharsets.US_ASCII),
                candidate.digest().getBytes(StandardCharsets.US_ASCII));
    }

    private static String digest(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Replay(String digest, Instant expiresAt) {
    }
}
