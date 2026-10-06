package org.las.sso.security;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.oidc.OidcReauthenticationService;
import org.las.sso.session.UserSessionService;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public final class LasAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final LoginAttemptService loginAttempts;
    private final RecentAuthenticationService recentAuthentication;
    private final UserSessionService userSessions;
    private final OidcReauthenticationService oidcReauthentication;
    private final org.las.sso.observability.SsoMetrics metrics;
    private final SavedRequestAwareAuthenticationSuccessHandler delegate =
            new SavedRequestAwareAuthenticationSuccessHandler();

    public LasAuthenticationSuccessHandler(
            LoginAttemptService loginAttempts,
            RecentAuthenticationService recentAuthentication,
            UserSessionService userSessions,
            OidcReauthenticationService oidcReauthentication,
            org.las.sso.observability.SsoMetrics metrics) {
        this.loginAttempts = loginAttempts;
        this.recentAuthentication = recentAuthentication;
        this.userSessions = userSessions;
        this.oidcReauthentication = oidcReauthentication;
        this.metrics = metrics;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        if (authentication.getPrincipal() instanceof LasUserPrincipal principal) {
            loginAttempts.recordSuccess(principal.userId());
            userSessions.registerLogin(principal, request);
            recentAuthentication.markPasswordAuthenticated(request);
            userSessions.updateRecentPasswordAuthentication(request);
            oidcReauthentication.completeAfterLogin(request, principal);
            metrics.login(org.las.sso.observability.SsoMetrics.LoginResult.SUCCESS,
                    org.las.sso.observability.SsoMetrics.LoginReason.NONE);
            if (principal.passwordChangeRequired()) {
                response.sendRedirect(request.getContextPath() + "/password-change-required");
                return;
            }
        }
        delegate.onAuthenticationSuccess(request, response, authentication);
    }
}
