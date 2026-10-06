package org.las.sso.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.PasswordReauthenticationLimiter;
import org.las.sso.security.RecentAuthenticationException;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.security.SameOriginVerifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public final class AccountSecurityController {

    private final RecentAuthenticationService recentAuthentication;
    private final RecoveryCodeRotationService recoveryCodeRotation;
    private final SameOriginVerifier sameOriginVerifier;
    private final PasswordReauthenticationLimiter reauthenticationLimiter;

    public AccountSecurityController(
            RecentAuthenticationService recentAuthentication,
            RecoveryCodeRotationService recoveryCodeRotation,
            SameOriginVerifier sameOriginVerifier,
            PasswordReauthenticationLimiter reauthenticationLimiter) {
        this.recentAuthentication = recentAuthentication;
        this.recoveryCodeRotation = recoveryCodeRotation;
        this.sameOriginVerifier = sameOriginVerifier;
        this.reauthenticationLimiter = reauthenticationLimiter;
    }

    @PostMapping("/reauthentication")
    ResponseEntity<Void> reauthenticate(
            Authentication authentication,
            @Valid @RequestBody ReauthenticationRequest body,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        LasUserPrincipal principal = principal(authentication);
        reauthenticationLimiter.consume(principal, request);
        recentAuthentication.reauthenticate(principal, body.password(), request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/recovery-codes/status")
    ResponseEntity<RecoveryCodeRotationService.Status> recoveryCodeStatus(
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(recoveryCodeRotation.status(principal(authentication).userId()));
    }

    @PostMapping("/recovery-codes/rotate")
    ResponseEntity<RecoveryCodeRotationService.RotationResult> rotateRecoveryCodes(
            Authentication authentication,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        LasUserPrincipal principal = principal(authentication);
        if (!recentAuthentication.isRecent(request)) {
            throw new RecentAuthenticationException("RECENT_AUTHENTICATION_REQUIRED");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(recoveryCodeRotation.rotate(principal.userId()));
    }

    private static LasUserPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)
                || principal.passwordChangeRequired()) {
            throw new RecentAuthenticationException("PASSWORD_CHANGE_REQUIRED");
        }
        return principal;
    }

    public record ReauthenticationRequest(
            @NotBlank @Size(max = 512) String password) {
    }
}
