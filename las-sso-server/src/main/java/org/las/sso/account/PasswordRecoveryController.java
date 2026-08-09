package org.las.sso.account;

import java.time.Duration;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.las.sso.api.RequestIdFilter;
import org.las.sso.security.ClientNetworkKey;
import org.las.sso.security.SameOriginVerifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recovery")
public class PasswordRecoveryController {

    static final String COOKIE_NAME = "__Host-las_recovery";

    private final PasswordRecoveryService recovery;
    private final ClientNetworkKey clientNetworkKey;
    private final SameOriginVerifier sameOriginVerifier;

    public PasswordRecoveryController(
            PasswordRecoveryService recovery,
            ClientNetworkKey clientNetworkKey,
            SameOriginVerifier sameOriginVerifier) {
        this.recovery = recovery;
        this.clientNetworkKey = clientNetworkKey;
        this.sameOriginVerifier = sameOriginVerifier;
    }

    @PostMapping("/sessions")
    ResponseEntity<Void> start(
            @Valid @RequestBody StartRecoveryRequest body,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        PasswordRecoveryService.CaptchaSubmission captcha = body.captcha() == null
                ? null
                : new PasswordRecoveryService.CaptchaSubmission(
                        body.captcha().challengeId(), body.captcha().payload());
        PasswordRecoveryService.RecoverySession session = recovery.start(
                body.username(),
                body.credential(),
                body.credentialType(),
                captcha,
                clientNetworkKey.fromRemoteAddress(request.getRemoteAddr()),
                requestId(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, recoveryCookie(
                        session.token(), PasswordRecoveryService.SESSION_LIFETIME).toString())
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PutMapping("/password")
    ResponseEntity<Void> complete(
            @CookieValue(name = COOKIE_NAME, required = false) String sessionToken,
            @Valid @RequestBody CompleteRecoveryRequest body,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        recovery.complete(sessionToken, body.newPassword(), requestId(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, recoveryCookie("", Duration.ZERO).toString())
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private static ResponseCookie recoveryCookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private static UUID requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId instanceof UUID value) return value;
        throw new IllegalStateException("Request ID filter must run before recovery operations");
    }

    public record StartRecoveryRequest(
            @NotBlank @Size(max = 24) String username,
            @NotBlank @Size(max = 64) String credential,
            @NotNull PasswordRecoveryService.CredentialType credentialType,
            @Valid CaptchaSubmission captcha) {
    }

    public record CaptchaSubmission(
            @NotNull UUID challengeId,
            @NotBlank @Size(max = 32_768) String payload) {
    }

    public record CompleteRecoveryRequest(
            @NotBlank @Size(max = 512) String newPassword) {
    }
}
