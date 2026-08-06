package org.las.sso.account;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.las.sso.api.RequestIdFilter;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationException;
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

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/profile")
    ResponseEntity<ProfileService.View> profile(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(profiles.profile(principal(authentication).userId()));
    }

    @PatchMapping("/profile")
    ResponseEntity<ProfileService.View> updateProfile(
            Authentication authentication,
            @Valid @RequestBody DisplayNameRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(profiles.updateDisplayName(
                        principal(authentication).userId(), body.displayName(),
                        requestId(request)));
    }

    @PostMapping("/username-change")
    ResponseEntity<ProfileService.View> changeUsername(
            Authentication authentication,
            @Valid @RequestBody UsernameChangeRequest body,
            HttpServletRequest request) {
        LasUserPrincipal principal = principal(authentication);
        ProfileService.View view = profiles.changeUsername(
                principal.userId(),
                body.newUsername(),
                body.currentPassword(),
                requestId(request));
        refreshPrincipalUsername(principal, view.username());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    private static void refreshPrincipalUsername(
            LasUserPrincipal current, String newUsername) {
        var updated = new LasUserPrincipal(
                current.userId(),
                newUsername,
                current.getPassword(),
                current.credentialsVersion(),
                current.isEnabled(),
                current.passwordChangeRequired(),
                current.getAuthorities());
        var authentication = org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken.authenticated(
                        updated, null, updated.getAuthorities());
        org.springframework.security.core.context.SecurityContextHolder
                .getContext().setAuthentication(authentication);
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

    public record DisplayNameRequest(
            @NotBlank @Size(max = 512) String displayName) {
    }

    public record UsernameChangeRequest(
            @NotBlank @Size(max = 24) String newUsername,
            @NotBlank @Size(max = 512) String currentPassword) {
    }
}
