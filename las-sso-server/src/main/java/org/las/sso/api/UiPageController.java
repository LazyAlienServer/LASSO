package org.las.sso.api;

import org.las.sso.account.AccountManagementQueryService;
import org.las.sso.account.DeletionRequestService;
import org.las.sso.account.RecoveryCodeRotationService;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.session.UserSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiPageController {

    private final AccountManagementQueryService accountManagement;
    private final RecoveryCodeRotationService recoveryCodes;
    private final UserSessionService userSessions;
    private final DeletionRequestService deletions;

    public UiPageController(
            AccountManagementQueryService accountManagement,
            RecoveryCodeRotationService recoveryCodes,
            UserSessionService userSessions,
            DeletionRequestService deletions) {
        this.accountManagement = accountManagement;
        this.recoveryCodes = recoveryCodes;
        this.userSessions = userSessions;
        this.deletions = deletions;
    }

    @GetMapping("/login")
    String login(Authentication authentication) {
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof LasUserPrincipal principal) {
            if (principal.passwordChangeRequired()) {
                return "redirect:/password-change-required";
            }
            return "redirect:/account";
        }
        return "login";
    }

    @GetMapping("/register")
    String register() {
        return "register";
    }

    @GetMapping("/password-recovery")
    String passwordRecovery() {
        return "password-recovery";
    }

    @GetMapping("/")
    String home(Authentication authentication) {
        LasUserPrincipal principal = principal(authentication);
        if (principal.passwordChangeRequired()) {
            return "redirect:/password-change-required";
        }
        return "redirect:/account";
    }

    @GetMapping("/account")
    String account(Authentication authentication, HttpServletRequest request, Model model) {
        LasUserPrincipal principal = principal(authentication);
        if (principal.passwordChangeRequired()) {
            return "redirect:/password-change-required";
        }
        populateAccountModel(model, principal);
        model.addAttribute("profile", accountManagement.profile(principal.userId()));
        model.addAttribute("recoveryStatus", recoveryCodes.status(principal.userId()));
        model.addAttribute("activeSessionCount", userSessions.sessions(principal.userId(), request).size());
        return "account";
    }

    @GetMapping("/account/sessions")
    String accountSessions(
            Authentication authentication,
            HttpServletRequest request,
            Model model) {
        LasUserPrincipal principal = requireReadyPrincipal(authentication);
        populateAccountModel(model, principal);
        model.addAttribute("sessions", userSessions.sessions(principal.userId(), request));
        return "account-sessions";
    }

    @GetMapping("/account/recovery-codes")
    String accountRecoveryCodes(Authentication authentication, Model model) {
        LasUserPrincipal principal = requireReadyPrincipal(authentication);
        populateAccountModel(model, principal);
        return "recovery-codes";
    }

    @GetMapping("/account/authorizations")
    String accountAuthorizations(Authentication authentication, Model model) {
        LasUserPrincipal principal = requireReadyPrincipal(authentication);
        populateAccountModel(model, principal);
        model.addAttribute("authorizations", accountManagement.authorizations(principal.userId()));
        return "account-authorizations";
    }

    @GetMapping("/account/delete")
    String accountDeletion(Authentication authentication, Model model) {
        LasUserPrincipal principal = requireReadyPrincipal(authentication);
        populateAccountModel(model, principal);
        model.addAttribute("deletion", deletions.status(principal.userId()));
        return "account-delete";
    }

    @GetMapping({"/account/security", "/password-change"})
    String accountSecurity(Authentication authentication) {
        LasUserPrincipal principal = principal(authentication);
        if (principal.passwordChangeRequired()) {
            return "redirect:/password-change-required";
        }
        return "redirect:/account/recovery-codes#password-change";
    }

    @GetMapping("/password-change-required")
    String passwordChangeRequired(Authentication authentication, Model model) {
        LasUserPrincipal principal = principal(authentication);
        if (!principal.passwordChangeRequired()) {
            return "redirect:/account/security";
        }
        populateSecurityModel(model, principal, true);
        return "security-settings";
    }

    private static void populateSecurityModel(
            Model model,
            LasUserPrincipal principal,
            boolean requiredChange) {
        model.addAttribute("username", principal.loginUsername());
        model.addAttribute("requiredChange", requiredChange);
    }

    private static void populateAccountModel(Model model, LasUserPrincipal principal) {
        model.addAttribute("username", principal.loginUsername());
        model.addAttribute("userId", principal.userId());
        model.addAttribute("administrator", principal.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SSO_ADMIN".equals(authority.getAuthority())));
    }

    private static LasUserPrincipal requireReadyPrincipal(Authentication authentication) {
        LasUserPrincipal principal = principal(authentication);
        if (principal.passwordChangeRequired()) {
            throw new IllegalStateException("Password change filter must redirect this request");
        }
        return principal;
    }

    private static LasUserPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)) {
            throw new IllegalStateException("Authenticated LAS principal required");
        }
        return principal;
    }
}
