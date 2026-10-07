package org.las.sso.oidc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.PasswordReauthenticationLimiter;
import org.las.sso.security.RecentAuthenticationException;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.security.SameOriginVerifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public final class OidcReauthenticationController {

    private final OidcReauthenticationService challenges;
    private final RecentAuthenticationService recentAuthentication;
    private final PasswordReauthenticationLimiter limiter;
    private final SameOriginVerifier sameOriginVerifier;

    public OidcReauthenticationController(
            OidcReauthenticationService challenges,
            RecentAuthenticationService recentAuthentication,
            PasswordReauthenticationLimiter limiter,
            SameOriginVerifier sameOriginVerifier) {
        this.challenges = challenges;
        this.recentAuthentication = recentAuthentication;
        this.limiter = limiter;
        this.sameOriginVerifier = sameOriginVerifier;
    }

    @GetMapping("/oauth2/reauthenticate")
    String form(
            Authentication authentication,
            @RequestParam String challenge,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        LasUserPrincipal principal = principal(authentication);
        challenges.requireChallenge(request, principal, challenge);
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("challenge", challenge);
        model.addAttribute("username", principal.loginUsername());
        return "oidc-reauthenticate";
    }

    @PostMapping("/oauth2/reauthenticate")
    String verify(
            Authentication authentication,
            @RequestParam String challenge,
            @RequestParam String password,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        sameOriginVerifier.verify(request);
        LasUserPrincipal principal = principal(authentication);
        challenges.requireChallenge(request, principal, challenge);
        limiter.consume(principal, request);
        try {
            if (password.isBlank() || password.length() > 512) {
                throw new RecentAuthenticationException("REAUTHENTICATION_FAILED");
            }
            recentAuthentication.reauthenticate(principal, password, request);
        } catch (RecentAuthenticationException exception) {
            if (!"REAUTHENTICATION_FAILED".equals(exception.code())) throw exception;
            model.addAttribute("failed", true);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return form(authentication, challenge, request, response, model);
        }
        response.setHeader("Cache-Control", "no-store");
        return "redirect:" + challenges.complete(request, principal, challenge);
    }

    private static LasUserPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)
                || principal.passwordChangeRequired()) {
            throw new RecentAuthenticationException("PASSWORD_CHANGE_REQUIRED");
        }
        return principal;
    }
}
