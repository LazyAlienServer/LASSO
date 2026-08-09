package org.las.sso.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.las.sso.api.RequestIdFilter;
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
@RequestMapping("/api/v1/admin/deletion-requests")
public class AdminDeletionRequestController {

    private final AdminDeletionRequestService deletions;
    private final AdminOperationGuard adminGuard;

    public AdminDeletionRequestController(
            AdminDeletionRequestService deletions,
            AdminOperationGuard adminGuard) {
        this.deletions = deletions;
        this.adminGuard = adminGuard;
    }

    @GetMapping
    ResponseEntity<AdminDeletionRequestService.DeletionPage> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(deletions.list(status, cursor, limit));
    }

    @PostMapping("/{requestId}/hold")
    ResponseEntity<Void> hold(
            Authentication authentication,
            @PathVariable UUID requestId,
            @Valid @RequestBody HoldRequest body,
            HttpServletRequest request) {
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
        deletions.hold(context.principal().userId(), context.sid(), requestId,
                body.reason(), body.holdUntil(), requestId(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{requestId}/release")
    ResponseEntity<Void> release(
            Authentication authentication,
            @PathVariable UUID requestId,
            @Valid @RequestBody ReleaseRequest body,
            HttpServletRequest request) {
        AdminOperationGuard.Context context = adminGuard.authorizeWrite(authentication, request);
        deletions.release(context.principal().userId(), context.sid(), requestId,
                body.reason(), requestId(request));
        return ResponseEntity.noContent().build();
    }

    private static UUID requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId instanceof UUID value) return value;
        throw new IllegalStateException("Request ID filter must run before admin operations");
    }

    public record HoldRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotNull OffsetDateTime holdUntil) {
    }

    public record ReleaseRequest(
            @NotBlank @Size(max = 500) String reason) {
    }
}
