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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/clients")
public class AdminClientController {

    private final AdminClientService clients;
    private final RecentAuthenticationService recentAuthentication;
    private final SameOriginVerifier sameOriginVerifier;
    private final UserSessionService userSessions;

    public AdminClientController(
            AdminClientService clients,
            RecentAuthenticationService recentAuthentication,
            SameOriginVerifier sameOriginVerifier,
            UserSessionService userSessions) {
        this.clients = clients;
        this.recentAuthentication = recentAuthentication;
        this.sameOriginVerifier = sameOriginVerifier;
        this.userSessions = userSessions;
    }

    @GetMapping
    ResponseEntity<AdminClientService.ClientPage> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clients.list(query, status, cursor, limit));
    }

    @GetMapping("/{clientId}")
    ResponseEntity<AdminClientService.ClientView> detail(@PathVariable String clientId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clients.detail(clientId));
    }

    @PostMapping
    ResponseEntity<AdminClientService.CreateResult> create(
            Authentication authentication,
            @Valid @RequestBody CreateBody body,
            HttpServletRequest request) {
        LasUserPrincipal principal = authorizeWrite(authentication, request);
        AdminClientService.CreateResult result = clients.create(
                new AdminClientService.CreateRequest(
                        body.clientId(),
                        body.clientName(),
                        body.clientType(),
                        body.redirectUris(),
                        body.postLogoutRedirectUris(),
                        body.scopes(),
                        body.resourceAudiences(),
                        body.backchannelLogoutUri()),
                principal.userId(), userSessions.currentSid(request),
                body.reason(), requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PatchMapping("/{clientId}")
    ResponseEntity<AdminClientService.PatchResult> patch(
            Authentication authentication,
            @PathVariable String clientId,
            @Valid @RequestBody PatchBody body,
            HttpServletRequest request) {
        LasUserPrincipal principal = authorizeWrite(authentication, request);
        AdminClientService.PatchResult result = clients.patch(
                clientId,
                new AdminClientService.PatchRequest(
                        body.clientName(),
                        body.clientType(),
                        body.redirectUris(),
                        body.postLogoutRedirectUris(),
                        body.scopes(),
                        body.resourceAudiences(),
                        body.backchannelLogoutUri()),
                principal.userId(), userSessions.currentSid(request),
                body.reason(), requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/{clientId}/disable")
    ResponseEntity<Void> disable(
            Authentication authentication,
            @PathVariable String clientId,
            @Valid @RequestBody ActionBody body,
            HttpServletRequest request) {
        LasUserPrincipal principal = authorizeWrite(authentication, request);
        clients.disable(clientId, principal.userId(), userSessions.currentSid(request),
                body.reason(), requestId(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{clientId}/enable")
    ResponseEntity<Void> enable(
            Authentication authentication,
            @PathVariable String clientId,
            @Valid @RequestBody ActionBody body,
            HttpServletRequest request) {
        LasUserPrincipal principal = authorizeWrite(authentication, request);
        clients.enable(clientId, principal.userId(), userSessions.currentSid(request),
                body.reason(), requestId(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{clientId}/secret-rotation")
    ResponseEntity<AdminClientService.RotationResult> rotateSecret(
            Authentication authentication,
            @PathVariable String clientId,
            @Valid @RequestBody ActionBody body,
            HttpServletRequest request) {
        LasUserPrincipal principal = authorizeWrite(authentication, request);
        AdminClientService.RotationResult result = clients.rotateSecret(
                clientId, principal.userId(), userSessions.currentSid(request),
                body.reason(), requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
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

    public record CreateBody(
            String clientId,
            @NotBlank @Size(max = 128) String clientName,
            @NotBlank String clientType,
            java.util.List<@Size(max = 500) String> redirectUris,
            java.util.List<@Size(max = 500) String> postLogoutRedirectUris,
            java.util.List<@Size(max = 40) String> scopes,
            java.util.List<@Size(max = 200) String> resourceAudiences,
            @Size(max = 500) String backchannelLogoutUri,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record PatchBody(
            @NotBlank @Size(max = 128) String clientName,
            @NotBlank String clientType,
            java.util.List<@Size(max = 500) String> redirectUris,
            java.util.List<@Size(max = 500) String> postLogoutRedirectUris,
            java.util.List<@Size(max = 40) String> scopes,
            java.util.List<@Size(max = 200) String> resourceAudiences,
            @Size(max = 500) String backchannelLogoutUri,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record ActionBody(
            @NotBlank @Size(max = 500) String reason) {
    }
}
