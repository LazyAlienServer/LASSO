package org.las.sso.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.SameOriginVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class PasswordChangeController {

    private final PasswordChangeService passwordChangeService;
    private final SameOriginVerifier sameOriginVerifier;

    public PasswordChangeController(
            PasswordChangeService passwordChangeService, SameOriginVerifier sameOriginVerifier) {
        this.passwordChangeService = passwordChangeService;
        this.sameOriginVerifier = sameOriginVerifier;
    }

    @PostMapping("/api/v1/me/password-change")
    ResponseEntity<Void> changePassword(
            Authentication authentication,
            @Valid @RequestBody PasswordChangeRequest body,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String currentCredential =
                principal.passwordChangeRequired()
                        ? body.temporaryPassword()
                        : body.currentPassword();
        passwordChangeService.changePassword(
                principal.userId(),
                principal.passwordChangeRequired(),
                currentCredential,
                body.newPassword());
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    record PasswordChangeRequest(
            @Size(max = 512) String currentPassword,
            @Size(max = 512) String temporaryPassword,
            @NotBlank @Size(max = 512) String newPassword) {}
}
