package org.las.sso.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.las.sso.api.RequestIdFilter;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationException;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.security.SameOriginVerifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/deletion-request")
public class AccountDeletionController {

    private final DeletionRequestService deletions;
    private final RecentAuthenticationService recentAuthentication;
    private final SameOriginVerifier sameOriginVerifier;
    private final OperationIdempotencyService idempotency;

    public AccountDeletionController(
            DeletionRequestService deletions,
            RecentAuthenticationService recentAuthentication,
            SameOriginVerifier sameOriginVerifier,
            OperationIdempotencyService idempotency) {
        this.deletions = deletions;
        this.recentAuthentication = recentAuthentication;
        this.sameOriginVerifier = sameOriginVerifier;
        this.idempotency = idempotency;
    }

    @PostMapping
    ResponseEntity<DeletionRequestService.RequestView> request(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody Request body,
            HttpServletRequest request) {
        LasUserPrincipal principal = principal(authentication);
        sameOriginVerifier.verify(request);
        recentAuthentication.reauthenticate(principal, body.password(), request);
        DeletionRequestService.RequestView view =
                idempotency.execute(
                        principal.userId(),
                        "ACCOUNT_DELETION_REQUEST",
                        idempotencyKey,
                        body.password(),
                        () -> deletions.request(principal.userId(), requestId(request)),
                        result ->
                                new OperationIdempotencyService.StoredResult(
                                        result.requestedAt() + "|" + result.executeAfter(),
                                        result.requestedAt()),
                        stored -> {
                            String[] values = stored.resultRef().split("\\|", 2);
                            return new DeletionRequestService.RequestView(
                                    "PENDING",
                                    java.time.OffsetDateTime.parse(values[0]),
                                    java.time.OffsetDateTime.parse(values[1]),
                                    null,
                                    null);
                        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    @DeleteMapping
    ResponseEntity<Void> cancel(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody Request body,
            HttpServletRequest request) {
        LasUserPrincipal principal = principal(authentication);
        sameOriginVerifier.verify(request);
        recentAuthentication.reauthenticate(principal, body.password(), request);
        idempotency.executeWithoutResult(
                principal.userId(),
                "ACCOUNT_DELETION_CANCEL",
                idempotencyKey,
                body.password(),
                () -> {
                    deletions.cancel(principal.userId(), requestId(request));
                    return Boolean.TRUE;
                },
                () -> Boolean.TRUE);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    ResponseEntity<DeletionRequestService.RequestView> status(Authentication authentication) {
        LasUserPrincipal principal = principal(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deletions.status(principal.userId()));
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
        throw new IllegalStateException("Request ID filter must run before deletion operations");
    }

    public record Request(@NotBlank @Size(max = 512) String password) {}
}
