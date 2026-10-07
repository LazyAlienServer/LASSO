package org.las.sso.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.las.sso.api.RequestIdFilter;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationException;
import org.las.sso.security.SameOriginVerifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {

    private final ProfileService profiles;
    private final SameOriginVerifier sameOriginVerifier;

    public ProfileController(ProfileService profiles, SameOriginVerifier sameOriginVerifier) {
        this.profiles = profiles;
        this.sameOriginVerifier = sameOriginVerifier;
    }

    @GetMapping("/profile")
    ResponseEntity<ProfileService.View> profile(Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(profiles.profile(principal(authentication).userId()));
    }

    @PatchMapping("/profile")
    ResponseEntity<ProfileService.View> updateProfile(
            Authentication authentication,
            @Valid @RequestBody ProfileUpdateRequest body,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        profiles.updateProfile(
                                principal(authentication).userId(),
                                body.displayName(),
                                body.avatarResourceId(),
                                requestId(request)));
    }

    @PostMapping("/username-change")
    ResponseEntity<ProfileService.View> changeUsername(
            Authentication authentication,
            @Valid @RequestBody UsernameChangeRequest body,
            HttpServletRequest request) {
        LasUserPrincipal principal = principal(authentication);
        sameOriginVerifier.verify(request);
        ProfileService.View view =
                profiles.changeUsername(
                        principal.userId(),
                        body.newUsername(),
                        body.currentPassword(),
                        requestId(request));
        refreshPrincipalUsername(principal, view.username());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    private static void refreshPrincipalUsername(LasUserPrincipal current, String newUsername) {
        var updated =
                new LasUserPrincipal(
                        current.userId(),
                        newUsername,
                        current.getPassword(),
                        current.credentialsVersion(),
                        current.isEnabled(),
                        current.passwordChangeRequired(),
                        current.getAuthorities());
        var authentication =
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated(updated, null, updated.getAuthorities());
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(authentication);
    }

    private static LasUserPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)) {
            throw new RecentAuthenticationException("REAUTHENTICATION_FAILED");
        }
        return principal;
    }

    private static UUID requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId instanceof UUID value) return value;
        throw new IllegalStateException("Request ID filter must run before profile operations");
    }

    public record ProfileUpdateRequest(
            @NotBlank @Size(max = 512) String displayName,
            @Size(max = 128) String avatarResourceId) {}

    public record UsernameChangeRequest(
            @NotBlank @Size(max = 24) String newUsername,
            @NotBlank @Size(max = 512) String currentPassword) {}
}
