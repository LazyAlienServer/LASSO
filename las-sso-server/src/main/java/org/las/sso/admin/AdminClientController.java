package org.las.sso.admin;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.las.sso.api.RequestIdFilter;
import org.las.sso.account.OperationIdempotencyService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/clients")
public class AdminClientController {

    private final AdminClientService clients;
    private final AdminOperationGuard adminGuard;
    private final OperationIdempotencyService idempotency;

    public AdminClientController(
            AdminClientService clients,
            AdminOperationGuard adminGuard,
            OperationIdempotencyService idempotency) {
        this.clients = clients;
        this.adminGuard = adminGuard;
        this.idempotency = idempotency;
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
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
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
                context.principal().userId(), context.sid(),
                body.reason(), requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PatchMapping("/{clientId}")
    ResponseEntity<AdminClientService.PatchResult> patch(
            Authentication authentication,
            @PathVariable String clientId,
            @Valid @RequestBody PatchBody body,
            HttpServletRequest request) {
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
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
                context.principal().userId(), context.sid(),
                body.reason(), requestId(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/{clientId}/disable")
    ResponseEntity<Void> disable(
            Authentication authentication,
            @PathVariable String clientId,
            @Valid @RequestBody ActionBody body,
            HttpServletRequest request) {
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
        clients.disable(clientId, context.principal().userId(), context.sid(),
                body.reason(), requestId(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{clientId}/enable")
    ResponseEntity<Void> enable(
            Authentication authentication,
            @PathVariable String clientId,
            @Valid @RequestBody ActionBody body,
            HttpServletRequest request) {
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
        clients.enable(clientId, context.principal().userId(), context.sid(),
                body.reason(), requestId(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{clientId}/secret-rotation")
    ResponseEntity<AdminClientService.RotationResult> rotateSecret(
            Authentication authentication,
            @PathVariable String clientId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ActionBody body,
            HttpServletRequest request) {
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
        AdminClientService.RotationResult result = idempotency.execute(
                context.principal().userId(), "ADMIN_CLIENT_SECRET_ROTATE",
                idempotencyKey, clientId + "\u0000" + body.reason(),
                () -> clients.rotateSecret(
                        clientId, context.principal().userId(), context.sid(),
                        body.reason(), requestId(request)),
                rotated -> new OperationIdempotencyService.StoredResult(
                        Long.toString(rotated.configVersion()), rotated.validFrom()),
                stored -> new AdminClientService.RotationResult(
                        null, stored.resultTime(), null,
                        Long.parseLong(stored.resultRef()), false, true));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
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
