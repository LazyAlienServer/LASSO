package org.las.sso.oidc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** Runs after Spring has validated client, redirect URI, scopes, PKCE and prompt. */
public final class OidcPasswordAuthenticationFilter extends OncePerRequestFilter {

    private final OidcReauthenticationService challenges;
    private final RecentAuthenticationService recentAuthentication;
    private final RequestCache requestCache;

    public OidcPasswordAuthenticationFilter(
            OidcReauthenticationService challenges,
            RecentAuthenticationService recentAuthentication,
            RequestCache requestCache) {
        this.challenges = challenges;
        this.recentAuthentication = recentAuthentication;
        this.requestCache = requestCache;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Object validated =
                request.getAttribute(
                        OAuth2AuthorizationCodeRequestAuthenticationToken.class.getName());
        if (!(validated instanceof OAuth2AuthorizationCodeRequestAuthenticationToken authorization)
                || !authorization.getScopes().contains(OidcScopes.OPENID)) {
            filterChain.doFilter(request, response);
            return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        LasUserPrincipal principal =
                authentication != null
                                && authentication.isAuthenticated()
                                && authentication.getPrincipal() instanceof LasUserPrincipal user
                        ? user
                        : null;
        String prompt = (String) authorization.getAdditionalParameters().getOrDefault("prompt", "");
        var prompts = Arrays.asList(prompt.split(" "));
        String maxAge = (String) authorization.getAdditionalParameters().get("max_age");
        Instant at = recentAuthentication.passwordAuthenticatedAt(request).orElse(null);
        boolean forced =
                prompts.contains("login") || (maxAge != null && Long.parseLong(maxAge) == 0);
        boolean expired =
                maxAge != null
                        && Long.parseLong(maxAge) > 0
                        && (at == null
                                || Instant.now().getEpochSecond() - at.getEpochSecond()
                                        > Long.parseLong(maxAge));
        boolean completed = principal != null && challenges.consumeCompletion(request, principal);
        if (principal == null || expired || (!completed && forced)) {
            response.setHeader("Cache-Control", "no-store");
            if (prompts.contains("none")) {
                var uri =
                        UriComponentsBuilder.fromUriString(authorization.getRedirectUri())
                                .queryParam("error", "login_required");
                if (authorization.getState() != null) {
                    uri.queryParam(
                            "state",
                            UriUtils.encode(authorization.getState(), StandardCharsets.UTF_8));
                }
                response.sendRedirect(uri.build(true).toUriString());
            } else {
                String id = challenges.begin(request, principal);
                if (principal == null) {
                    requestCache.saveRequest(request, response);
                    response.sendRedirect(request.getContextPath() + "/login");
                } else {
                    response.sendRedirect(
                            request.getContextPath() + "/oauth2/reauthenticate?challenge=" + id);
                }
            }
            return;
        }
        filterChain.doFilter(request, response);
    }
}
