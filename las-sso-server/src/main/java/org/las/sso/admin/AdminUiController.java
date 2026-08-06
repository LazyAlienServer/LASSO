package org.las.sso.admin;

import java.util.UUID;

import org.las.sso.identity.LasUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminUiController {

    private static final int PAGE_SIZE = 8;

    private final AdminUserManagementService users;
    private final AdminAuditQueryService auditEvents;
    private final AdminDeletionRequestService deletionRequests;
    private final AdminClientService clients;

    public AdminUiController(
            AdminUserManagementService users,
            AdminAuditQueryService auditEvents,
            AdminDeletionRequestService deletionRequests,
            AdminClientService clients) {
        this.users = users;
        this.auditEvents = auditEvents;
        this.deletionRequests = deletionRequests;
        this.clients = clients;
    }

    @GetMapping("/admin/users")
    String users(
            Authentication authentication,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(required = false) UUID selected,
            Model model) {
        LasUserPrincipal principal = principal(authentication);
        AdminUserManagementService.UserPage page = users.users(query, status, cursor, PAGE_SIZE);
        UUID selectedId = selected;
        if (selectedId == null && !page.items().isEmpty()) {
            selectedId = page.items().getFirst().userId();
        }
        model.addAttribute("username", principal.loginUsername());
        model.addAttribute("actorUserId", principal.userId());
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("status", status == null ? "" : status);
        model.addAttribute("users", page.items());
        model.addAttribute("nextCursor", page.nextCursor());
        model.addAttribute("selectedUser", selectedId == null ? null : users.user(selectedId));
        return "admin-users";
    }

    @GetMapping("/admin/audit")
    String audit(
            Authentication authentication,
            @RequestParam(defaultValue = "audit") String kind,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            Model model) {
        LasUserPrincipal principal = principal(authentication);
        boolean securityKind = "security".equals(kind);
        model.addAttribute("username", principal.loginUsername());
        model.addAttribute("kind", securityKind ? "security" : "audit");
        model.addAttribute("action", action == null ? "" : action);
        model.addAttribute("type", type == null ? "" : type);
        model.addAttribute("severity", severity == null ? "" : severity);
        model.addAttribute("targetId", targetId == null ? "" : targetId);
        model.addAttribute("userId", userId == null ? "" : userId);
        model.addAttribute("from", from == null ? "" : from);
        model.addAttribute("to", to == null ? "" : to);
        if (securityKind) {
            model.addAttribute("events", auditEvents.securityEvents(
                    type, severity, userId, from, to, cursor, limit));
        } else {
            model.addAttribute("events", auditEvents.auditEvents(
                    action, targetId, from, to, cursor, limit));
        }
        model.addAttribute("auditActions", AuditLabels.auditActions());
        model.addAttribute("securityEvents", AuditLabels.securityEvents());
        return "admin-audit";
    }

    @GetMapping("/admin/deletion-requests")
    String deletionRequests(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            Model model) {
        LasUserPrincipal principal = principal(authentication);
        model.addAttribute("username", principal.loginUsername());
        model.addAttribute("status", status == null ? "" : status);
        model.addAttribute("requests", deletionRequests.list(status, cursor, limit));
        return "admin-deletion-requests";
    }

    @GetMapping("/admin/clients")
    String clients(
            Authentication authentication,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            Model model) {
        LasUserPrincipal principal = principal(authentication);
        model.addAttribute("username", principal.loginUsername());
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("status", status == null || status.isBlank() ? "ACTIVE" : status);
        model.addAttribute("clients", clients.list(query, status, cursor, limit));
        return "admin-clients";
    }

    private static LasUserPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)
                || principal.getAuthorities().stream()
                        .noneMatch(authority -> "ROLE_SSO_ADMIN".equals(authority.getAuthority()))) {
            throw new AdminUserManagementException("ADMIN_ACCESS_DENIED");
        }
        return principal;
    }
}
