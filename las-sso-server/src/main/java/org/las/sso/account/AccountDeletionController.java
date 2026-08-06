package org.las.sso.account;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.las.sso.api.RequestIdFilter;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationException;
import org.las.sso.security.RecentAuthenticationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/deletion-request")
public class AccountDeletionController {

    private final DeletionRequestService deletions;
    private final RecentAuthenticationService recentAuthentication;

    public AccountDeletionController(
            DeletionRequestService deletions,
            RecentAuthenticationService recentAuthentication) {
        this.deletions = deletions;
        this.recentAuthentication = recentAuthentication;
    }

    @PostMapping
    ResponseEntity<DeletionRequestService.RequestView> request(
            Authentication authentication,
            @Valid @RequestBody Request body,
            HttpServletRequest request) {
        LasUserPrincipal principal = principal(authentication);
        recentAuthentication.reauthenticate(principal, body.password(), request);
        DeletionRequestService.RequestView view = deletions.request(
                principal.userId(), requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    @DeleteMapping
    ResponseEntity<Void> cancel(Authentication authentication, HttpServletRequest request) {
        LasUserPrincipal principal = principal(authentication);
        deletions.cancel(principal.userId(), requestId(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    ResponseEntity<DeletionRequestService.RequestView> status(Authentication authentication) {
        LasUserPrincipal principal = principal(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
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

    public record Request(
            @NotBlank @Size(max = 512) String password) {
    }
}
