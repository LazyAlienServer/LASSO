package org.las.sso.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.las.sso.account.RegistrationApplicationService;
import org.las.sso.security.AltchaChallengeService;
import org.las.sso.security.ClientNetworkKey;
import org.las.sso.security.SameOriginVerifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RegistrationController {

    private final AltchaChallengeService captcha;
    private final RegistrationApplicationService registrations;
    private final ClientNetworkKey clientNetworkKey;
    private final SameOriginVerifier sameOriginVerifier;
    private final org.las.sso.observability.SsoMetrics metrics;

    public RegistrationController(
            AltchaChallengeService captcha,
            RegistrationApplicationService registrations,
            ClientNetworkKey clientNetworkKey,
            SameOriginVerifier sameOriginVerifier,
            org.las.sso.observability.SsoMetrics metrics) {
        this.captcha = captcha;
        this.registrations = registrations;
        this.clientNetworkKey = clientNetworkKey;
        this.sameOriginVerifier = sameOriginVerifier;
        this.metrics = metrics;
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    @PostMapping("/captcha/challenges")
    ResponseEntity<AltchaChallengeService.IssuedChallenge> issueChallenge(
            @Valid @RequestBody ChallengeRequest body,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        var challenge = captcha.issue(
                body.purpose(), clientNetworkKey.fromRemoteAddress(request.getRemoteAddr()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(challenge);
    }

    @PostMapping("/registrations")
    ResponseEntity<RegistrationResponse> register(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody RegistrationRequest body,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        var result = registrations.register(
                idempotencyKey,
                body.username(),
                body.displayName(),
                body.password(),
                body.captcha().challengeId(),
                body.captcha().payload(),
                clientNetworkKey.fromRemoteAddress(request.getRemoteAddr()));
        if (!result.idempotentReplay()) metrics.committedRegistrationSuccess();
        var response = new RegistrationResponse(
                new RegisteredUser(result.userId(), result.username(), result.displayName()),
                result.recoveryCodes(),
                !result.idempotentReplay(),
                result.idempotentReplay());
        HttpStatus status = result.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .location(URI.create("/api/v1/users/" + result.userId()))
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    public record ChallengeRequest(@NotNull AltchaChallengeService.Purpose purpose) {
    }

    public record RegistrationRequest(
            @NotBlank @Size(max = 24) String username,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Size(max = 512) String password,
            @NotNull @Valid CaptchaSubmission captcha) {
    }

    public record CaptchaSubmission(
            @NotNull UUID challengeId,
            @NotBlank @Size(max = 32_768) String payload) {
    }

    public record RegistrationResponse(
            RegisteredUser user,
            List<String> recoveryCodes,
            boolean secretsAvailable,
            boolean idempotentReplay) {
    }

    public record RegisteredUser(UUID id, String username, String displayName) {
    }

    public record CsrfResponse(String headerName, String parameterName, String token) {
    }
}
