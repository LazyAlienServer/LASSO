package org.las.sso.account;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.las.sso.api.RequestIdFilter;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationException;
import org.las.sso.security.SameOriginVerifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/authorizations")
public final class AccountAuthorizationController {

    private final AccountManagementQueryService accountManagement;
    private final AccountAuthorizationService authorizations;
    private final SameOriginVerifier sameOriginVerifier;

    public AccountAuthorizationController(
            AccountManagementQueryService accountManagement,
            AccountAuthorizationService authorizations,
            SameOriginVerifier sameOriginVerifier) {
        this.accountManagement = accountManagement;
        this.authorizations = authorizations;
        this.sameOriginVerifier = sameOriginVerifier;
    }

    @GetMapping
    ResponseEntity<List<AccountManagementQueryService.Authorization>> authorizations(
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(accountManagement.authorizations(principal(authentication).userId()));
    }

    @DeleteMapping("/{clientId}")
    ResponseEntity<Void> revoke(
            Authentication authentication,
            @PathVariable String clientId,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        authorizations.revoke(principal(authentication).userId(), clientId, requestId(request));
        return ResponseEntity.noContent().build();
    }

    private static LasUserPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)
                || principal.passwordChangeRequired()) {
            throw new RecentAuthenticationException("PASSWORD_CHANGE_REQUIRED");
        }
        return principal;
    }

    private static UUID requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId instanceof UUID value) return value;
        throw new IllegalStateException(
                "Request ID filter must run before authorization operations");
    }
}
