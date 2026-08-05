package org.las.sso.client;

import java.net.URI;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LasSsoSessionController {

    private final LasSsoClientProperties properties;

    public LasSsoSessionController(LasSsoClientProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/auth/login")
    ResponseEntity<Void> login() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/oauth2/authorization/" + properties.registrationId()))
                .build();
    }

    @GetMapping("/api/auth/session")
    ResponseEntity<SessionSnapshot> session(Authentication authentication) {
        SessionSnapshot snapshot = snapshot(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(snapshot);
    }

    static SessionSnapshot snapshot(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2)
                || !(oauth2.getPrincipal() instanceof OidcUser oidcUser)) {
            return new SessionSnapshot(false, null);
        }

        String username = oidcUser.getPreferredUsername();
        if (username == null || username.isBlank()) {
            username = oidcUser.getSubject();
        }
        return new SessionSnapshot(true, new UserSnapshot(
                oidcUser.getSubject(), username, Map.of()));
    }

    public record SessionSnapshot(boolean authenticated, UserSnapshot user) {
    }

    public record UserSnapshot(String sub, String username, Map<String, Object> businessPermissions) {
    }
}
