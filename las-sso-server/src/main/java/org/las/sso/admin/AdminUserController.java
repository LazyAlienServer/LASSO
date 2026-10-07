package org.las.sso.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.las.sso.account.OperationIdempotencyService;
import org.las.sso.api.RequestIdFilter;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserManagementService users;
    private final AdminRecoveryCredentialService recoveryCredentials;
    private final AdminOperationGuard adminGuard;
    private final OperationIdempotencyService idempotency;

    public AdminUserController(
            AdminUserManagementService users,
            AdminRecoveryCredentialService recoveryCredentials,
            AdminOperationGuard adminGuard,
            OperationIdempotencyService idempotency) {
        this.users = users;
        this.recoveryCredentials = recoveryCredentials;
        this.adminGuard = adminGuard;
        this.idempotency = idempotency;
    }

    @GetMapping
    ResponseEntity<AdminUserManagementService.UserPage> users(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
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
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ActionRequest body,
            HttpServletRequest request) {
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
        idempotency.executeWithoutResult(
                context.principal().userId(),
                "ADMIN_USER_FREEZE",
                idempotencyKey,
                userId + "\u0000" + body.reason(),
                () -> {
                    users.freeze(
                            context.principal().userId(),
                            context.sid(),
                            userId,
                            body.reason(),
                            requestId(request));
                    return Boolean.TRUE;
                },
                () -> Boolean.TRUE);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/unfreeze")
    ResponseEntity<Void> unfreeze(
            Authentication authentication,
            @PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ActionRequest body,
            HttpServletRequest request) {
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
        idempotency.executeWithoutResult(
                context.principal().userId(),
                "ADMIN_USER_UNFREEZE",
                idempotencyKey,
                userId + "\u0000" + body.reason(),
                () -> {
                    users.unfreeze(
                            context.principal().userId(),
                            context.sid(),
                            userId,
                            body.reason(),
                            requestId(request));
                    return Boolean.TRUE;
                },
                () -> Boolean.TRUE);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/revoke-sessions")
    ResponseEntity<Void> revokeSessions(
            Authentication authentication,
            @PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ActionRequest body,
            HttpServletRequest request) {
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
        idempotency.executeWithoutResult(
                context.principal().userId(),
                "ADMIN_USER_REVOKE_SESSIONS",
                idempotencyKey,
                userId + "\u0000" + body.reason(),
                () -> {
                    users.revokeSessions(
                            context.principal().userId(),
                            context.sid(),
                            userId,
                            body.reason(),
                            requestId(request));
                    return Boolean.TRUE;
                },
                () -> Boolean.TRUE);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/recovery-credentials")
    ResponseEntity<AdminRecoveryCredentialService.IssuedCredential> issueRecoveryCredential(
            Authentication authentication,
            @PathVariable UUID userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ActionRequest body,
            HttpServletRequest request) {
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
        AdminRecoveryCredentialService.IssuedCredential issued =
                idempotency.execute(
                        context.principal().userId(),
                        "ADMIN_RECOVERY_CREDENTIAL_ISSUE",
                        idempotencyKey,
                        userId + "\u0000" + body.reason(),
                        () ->
                                recoveryCredentials.issue(
                                        context.principal().userId(),
                                        context.sid(),
                                        userId,
                                        body.reason(),
                                        requestId(request)),
                        result ->
                                new OperationIdempotencyService.StoredResult(
                                        userId.toString(), result.expiresAt()),
                        stored ->
                                new AdminRecoveryCredentialService.IssuedCredential(
                                        null, stored.resultTime(), false, true));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(issued);
    }

    private static UUID requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId instanceof UUID value) return value;
        throw new IllegalStateException("Request ID filter must run before admin operations");
    }

    public record ActionRequest(@NotBlank @Size(max = 500) String reason) {}
}
