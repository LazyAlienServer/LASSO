package org.las.sso.client;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Shared LAS protocol/lifecycle wiring. Applications retain ownership of routing and authorization.
 */
public final class LasSsoSecurityConfigurer {

    private final ClientRegistrationRepository registrations;
    private final LasSsoAuthorizedClientRepository authorizedClients;
    private final LasSsoClientProperties properties;
    private final LasSsoAuthorizedClientRefresher refresher;
    private final LasSsoBackChannelSessionStore sessions;
    private final LasSsoCurrentApplicationLogoutHandler logout;
    private final LasSsoClientErrorHandler errors;

    public LasSsoSecurityConfigurer(
            ClientRegistrationRepository registrations,
            LasSsoAuthorizedClientRepository authorizedClients,
            LasSsoClientProperties properties,
            LasSsoAuthorizedClientRefresher refresher,
            LasSsoBackChannelSessionStore sessions,
            LasSsoCurrentApplicationLogoutHandler logout,
            LasSsoClientErrorHandler errors) {
        this.registrations = registrations;
        this.authorizedClients = authorizedClients;
        this.properties = properties;
        this.refresher = refresher;
        this.sessions = sessions;
        this.logout = logout;
        this.errors = errors;
    }

    /**
     * Apply once per chain; pass the application's existing success handler, not an unwrapped
     * override later.
     */
    public void configure(HttpSecurity http, AuthenticationSuccessHandler successHandler)
            throws Exception {
        configure(http, successHandler, builder -> {});
    }

    /**
     * Application-owned standard authorization customization (for example prompt/max_age). Never
     * forwards arbitrary browser parameters. The application must retain Spring's
     * state/nonce/client/redirect and PKCE attributes, and independently consume signed password
     * evidence for its own operation/session after login succeeds.
     */
    public void configure(
            HttpSecurity http,
            AuthenticationSuccessHandler successHandler,
            java.util.function.Consumer<OAuth2AuthorizationRequest.Builder> authorizationCustomizer)
            throws Exception {
        java.util.Objects.requireNonNull(successHandler, "successHandler");
        java.util.Objects.requireNonNull(authorizationCustomizer, "authorizationCustomizer");
        if (http.getSharedObject(Applied.class) != null) {
            throw new IllegalStateException(
                    "LAS SSO security wiring may be applied only once per chain");
        }
        http.setSharedObject(Applied.class, new Applied());
        var resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce().andThen(authorizationCustomizer));
        var clock = java.time.Clock.systemUTC();
        http.oauth2Login(
                oauth2 ->
                        oauth2.authorizationEndpoint(
                                        endpoint ->
                                                endpoint.authorizationRequestResolver(
                                                                new LasSsoAuthorizationRequestResolver(
                                                                        resolver, clock))
                                                        .authorizationRequestRepository(
                                                                new LasSsoAuthorizationRequestRepository(
                                                                        clock)))
                                .authorizedClientRepository(authorizedClients)
                                .successHandler(
                                        new LasSsoAuthenticationSuccessHandler(
                                                properties,
                                                sessions,
                                                logout,
                                                errors,
                                                successHandler)));
        http.logout(
                config ->
                        config.logoutUrl("/api/auth/logout")
                                .logoutSuccessUrl(properties.logoutSuccessUri())
                                .addLogoutHandler(logout)
                                .invalidateHttpSession(true)
                                .deleteCookies(properties.sessionCookieName()));
        http.csrf(
                csrf ->
                        csrf.ignoringRequestMatchers(
                                PathPatternRequestMatcher.pathPattern(
                                        HttpMethod.POST, "/internal/oidc/backchannel-logout")));
        http.addFilterBefore(
                new LasSsoReturnToValidationFilter(properties),
                OAuth2AuthorizationRequestRedirectFilter.class);
        http.addFilterAfter(
                new LasSsoAuthorizedClientRefreshFilter(
                        properties, refresher, authorizedClients, errors),
                OAuth2LoginAuthenticationFilter.class);
        http.addFilterAfter(
                new LasSsoSessionRegistrationFilter(
                        new LasSsoSessionRegistration(properties, sessions, logout, errors)),
                LasSsoAuthorizedClientRefreshFilter.class);
    }

    private static final class Applied {}
}
