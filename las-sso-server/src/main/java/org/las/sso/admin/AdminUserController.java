package org.las.sso.admin;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.las.sso.api.RequestIdFilter;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationException;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.security.SameOriginVerifier;
import org.las.sso.session.UserSessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserManagementService users;
    private final RecentAuthenticationService recentAuthentication;
    private final SameOriginVerifier sameOriginVerifier;
    private final UserSessionService userSessions;

    public AdminUserController(
            AdminUserManagementService users,
            RecentAuthenticationService recentAuthentication,
            SameOriginVerifier sameOriginVerifier,
            UserSessionService userSessions) {
        this.users = users;
        this.recentAuthentication = recentAuthentication;
        this.sameOriginVerifier = sameOriginVerifier;
        this.userSessions = userSessions;
    }

    @GetMapping
    ResponseEntity<AdminUserManagementService.UserPage> users(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(users.users(query, status, cursor, limit));
    }

    @GetMapping("/{userId}")
    ResponseEntity<AdminUserManagementService.UserDetails> user(@PathVariable UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(users.user(userId));
    }

    @PostMapping("/{userId}/freeze")
    ResponseEntity<Void> freeze(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody ActionRequest body,
            HttpServletRequest request) {
        LasUserPrincipal principal = authorizeWrite(authentication, request);
        users.freeze(principal.userId(), userSessions.currentSid(request), userId,
                body.reason(), requestId(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/unfreeze")
    ResponseEntity<Void> unfreeze(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody ActionRequest body,
            HttpServletRequest request) {
        LasUserPrincipal principal = authorizeWrite(authentication, request);
        users.unfreeze(principal.userId(), userSessions.currentSid(request), userId,
                body.reason(), requestId(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/revoke-sessions")
    ResponseEntity<Void> revokeSessions(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody ActionRequest body,
            HttpServletRequest request) {
        LasUserPrincipal principal = authorizeWrite(authentication, request);
        users.revokeSessions(principal.userId(), userSessions.currentSid(request), userId,
                body.reason(), requestId(request));
        return ResponseEntity.noContent().build();
    }

    private LasUserPrincipal authorizeWrite(
            Authentication authentication,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)
                || principal.getAuthorities().stream()
                        .noneMatch(authority -> "ROLE_SSO_ADMIN".equals(authority.getAuthority()))) {
            throw new AdminUserManagementException("ADMIN_ACCESS_DENIED");
        }
        if (!recentAuthentication.isRecent(request)) {
            throw new RecentAuthenticationException("RECENT_AUTHENTICATION_REQUIRED");
        }
        return principal;
    }

    private static UUID requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId instanceof UUID value) return value;
        throw new IllegalStateException("Request ID filter must run before admin operations");
    }

    public record ActionRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
