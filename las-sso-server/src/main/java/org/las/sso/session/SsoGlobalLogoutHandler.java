package org.las.sso.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.las.sso.identity.LasUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

@Component
public class SsoGlobalLogoutHandler implements LogoutHandler {

    private final UserSessionService sessions;

    public SsoGlobalLogoutHandler(UserSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        Authentication principalAuthentication = authentication;
        if (authentication instanceof OidcLogoutAuthenticationToken oidcLogout
                && oidcLogout.isPrincipalAuthenticated()
                && oidcLogout.getPrincipal() instanceof Authentication nested) {
            principalAuthentication = nested;
        }
        if (principalAuthentication != null
                && principalAuthentication.getPrincipal() instanceof LasUserPrincipal principal) {
            sessions.logoutAll(principal.userId(), "SSO_LOGOUT");
        } else if (authentication instanceof OidcLogoutAuthenticationToken oidcLogout
                && oidcLogout.getIdToken() != null
                && oidcLogout.getIdToken().getSubject() != null) {
            try {
                sessions.logoutAll(
                        UUID.fromString(oidcLogout.getIdToken().getSubject()), "SSO_LOGOUT");
            } catch (IllegalArgumentException ignored) {
                // Ignore a non-LAS subject after the protocol handler has completed validation.
            }
        }
    }
}
