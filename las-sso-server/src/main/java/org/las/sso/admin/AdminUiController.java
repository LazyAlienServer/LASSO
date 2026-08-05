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

    public AdminUiController(AdminUserManagementService users) {
        this.users = users;
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
