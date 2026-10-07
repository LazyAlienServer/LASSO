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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
        if (!(authentication.getPrincipal() instanceof LasUserPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
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

    @ExceptionHandler({PasswordChangeException.class, PasswordPolicyException.class})
    ResponseEntity<PasswordChangeError> passwordChangeError(RuntimeException exception) {
        String code =
                exception instanceof PasswordChangeException change
                        ? change.code()
                        : ((PasswordPolicyException) exception).code();
        HttpStatus status =
                "CURRENT_PASSWORD_INVALID".equals(code)
                        ? HttpStatus.UNAUTHORIZED
                        : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new PasswordChangeError(code));
    }

    record PasswordChangeRequest(
            @Size(max = 512) String currentPassword,
            @Size(max = 512) String temporaryPassword,
            @NotBlank @Size(max = 512) String newPassword) {}

    record PasswordChangeError(String code) {}
}
