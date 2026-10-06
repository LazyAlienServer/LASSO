package org.las.sso.client;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class LasSsoBackChannelLogoutController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LasSsoBackChannelLogoutController.class);

    private static final String LOGOUT_EVENT =
            "http://schemas.openid.net/event/backchannel-logout";
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(2);

    private final JwtDecoder jwtDecoder;
    private final String clientId;
    private final LasSsoBackChannelSessionStore sessions;
    private final LasSsoLogoutReplayStore replayStore;
    private final Clock clock;
    private final String issuer;

    LasSsoBackChannelLogoutController(
            @Qualifier("lasSsoLogoutTokenDecoder") JwtDecoder jwtDecoder,
            String clientId,
            LasSsoBackChannelSessionStore sessions,
            LasSsoLogoutReplayStore replayStore) {
        this(jwtDecoder, clientId, sessions, replayStore, null, Clock.systemUTC());
    }

    LasSsoBackChannelLogoutController(JwtDecoder jwtDecoder, String clientId,
            LasSsoBackChannelSessionStore sessions, LasSsoLogoutReplayStore replayStore,
            String issuer, Clock clock) {
        this.jwtDecoder = jwtDecoder;
        this.clientId = clientId;
        this.sessions = sessions;
        this.replayStore = replayStore;
        this.issuer = issuer;
        this.clock = clock;
    }

    LasSsoBackChannelLogoutController(
            @Qualifier("lasSsoLogoutTokenDecoder") JwtDecoder jwtDecoder,
            String clientId,
            LasSsoBackChannelSessionStore sessions) {
        this(jwtDecoder, clientId, sessions, new DefaultLasSsoLogoutReplayStore());
    }

    @PostMapping(path = "/internal/oidc/backchannel-logout",
            consumes = "application/x-www-form-urlencoded")
    ResponseEntity<Void> logout(@RequestParam("logout_token") String logoutToken) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(logoutToken);
            validate(jwt);
        } catch (BadJwtException | IllegalArgumentException | ClassCastException | java.time.DateTimeException exception) {
            LOGGER.warn("Rejected OIDC Back-Channel Logout Token: failureType={}", exception.getClass().getSimpleName());
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        } catch (JwtException exception) {
            LOGGER.error("Logout Token decoder unavailable: failureType={}", exception.getClass().getSimpleName());
            return unavailable();
        }
        try {
            var result = replayStore.process(jwt.getIssuer().toString(), jwt.getId(),
                    DefaultLasSsoLogoutReplayStore.sha256Base64Url(logoutToken),
                    jwt.getExpiresAt().plus(CLOCK_SKEW),
                    () -> sessions.invalidate(jwt.getClaimAsString("sid"), jwt.getSubject()));
            return switch (java.util.Objects.requireNonNull(result, "Replay result")) {
                case SUCCESS -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
                case IN_PROGRESS -> unavailable();
            };
        } catch (RuntimeException exception) {
            LOGGER.error("Back-channel logout execution unavailable: failureType={}", exception.getClass().getSimpleName());
            return unavailable();
        }
    }

    private static ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();
    }

    private void validate(Jwt jwt) {
        if (!"logout+jwt".equals(jwt.getHeaders().get("typ"))
                || !"RS256".equals(jwt.getHeaders().get("alg"))
                || !(jwt.getClaim("jti") instanceof String jti) || jti.isBlank()
                || !(jwt.getClaim("iss") instanceof String tokenIssuer) || tokenIssuer.isBlank()
                || (issuer != null && !issuer.equals(tokenIssuer))
                || jwt.getIssuer() == null
                || jwt.getAudience() == null
                || !jwt.getAudience().contains(clientId)
                || jwt.hasClaim("nonce")) {
            throw new IllegalArgumentException("Invalid logout token envelope");
        }
        Object rawEvents = jwt.getClaim("events");
        if (!(rawEvents instanceof Map<?, ?> events)
                || !(events.get(LOGOUT_EVENT) instanceof Map<?, ?> logoutEvent) || !logoutEvent.isEmpty()) {
            throw new IllegalArgumentException("Invalid logout event");
        }
        if (!(jwt.getClaim("sid") instanceof String sid) || sid.isBlank()
                || (jwt.hasClaim("sub") && (!(jwt.getClaim("sub") instanceof String subject) || subject.isBlank()))) {
            throw new IllegalArgumentException("First-party logout requires sid and a valid optional subject");
        }
        Instant now = clock.instant();
        Instant issued = jwt.getIssuedAt();
        Instant expires = jwt.getExpiresAt();
        if (issued == null || expires == null || !expires.isAfter(issued)
                || Duration.between(issued, expires).compareTo(MAX_LIFETIME) > 0
                || issued.isAfter(now.plus(CLOCK_SKEW))
                || issued.isBefore(now.minus(MAX_LIFETIME).minus(CLOCK_SKEW))
                || !expires.plus(CLOCK_SKEW).isAfter(now)) {
            throw new IllegalArgumentException("Invalid logout token lifetime");
        }
    }

}
