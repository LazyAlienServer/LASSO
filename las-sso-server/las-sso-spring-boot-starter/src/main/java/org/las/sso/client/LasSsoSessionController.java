package org.las.sso.client;

import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class LasSsoSessionController {

    private final LasSsoClientProperties properties;

    public LasSsoSessionController(LasSsoClientProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/auth/login")
    ResponseEntity<Void> login(HttpServletRequest request) {
        try {
            String target = LasSsoReturnTo.parameter(request);
            var location =
                    UriComponentsBuilder.fromPath(
                            request.getContextPath()
                                    + "/oauth2/authorization/"
                                    + properties.registrationId());
            if (target != null) location.queryParam("returnTo", "{target}");
            URI uri =
                    target == null
                            ? location.build().toUri()
                            : location.encode().buildAndExpand(target).toUri();
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(uri)
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
    }

    @GetMapping("/api/auth/session")
    ResponseEntity<SessionSnapshot> session(Authentication authentication) {
        SessionSnapshot snapshot = snapshot(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshot);
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
        return new SessionSnapshot(
                true, new UserSnapshot(oidcUser.getSubject(), username, Map.of()));
    }

    public record SessionSnapshot(boolean authenticated, UserSnapshot user) {}

    public record UserSnapshot(
            String sub, String username, Map<String, Object> businessPermissions) {}
}
