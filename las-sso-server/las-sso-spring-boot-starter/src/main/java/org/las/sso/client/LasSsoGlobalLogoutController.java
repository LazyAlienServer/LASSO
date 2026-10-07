package org.las.sso.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public final class LasSsoGlobalLogoutController {

    private static final String LOGOUT_STATE =
            LasSsoGlobalLogoutController.class.getName() + ".STATE";
    private static final String LOGOUT_STATE_EXPIRES_AT =
            LasSsoGlobalLogoutController.class.getName() + ".EXPIRES_AT";

    private final ClientRegistrationRepository registrations;
    private final LasSsoClientProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    LasSsoGlobalLogoutController(
            ClientRegistrationRepository registrations, LasSsoClientProperties properties) {
        this.registrations = registrations;
        this.properties = properties;
    }

    @PostMapping("/api/auth/logout/global")
    ResponseEntity<Void> logoutGlobal(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2)
                || !(oauth2.getPrincipal() instanceof OidcUser oidcUser)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        ClientRegistration registration =
                registrations.findByRegistrationId(oauth2.getAuthorizedClientRegistrationId());
        if (registration == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        Object endpointValue =
                registration
                        .getProviderDetails()
                        .getConfigurationMetadata()
                        .get("end_session_endpoint");
        if (endpointValue == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }

        String state = newState();
        String postLogoutRedirectUri = resolvePostLogoutRedirectUri(request);
        URI endpoint =
                UriComponentsBuilder.fromUriString(endpointValue.toString())
                        .queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue())
                        .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
                        .queryParam("state", state)
                        .encode(StandardCharsets.UTF_8)
                        .build()
                        .toUri();

        new SecurityContextLogoutHandler().logout(request, response, authentication);
        HttpSession pendingLogout = request.getSession(true);
        pendingLogout.setAttribute(LOGOUT_STATE, state);
        pendingLogout.setAttribute(
                LOGOUT_STATE_EXPIRES_AT, Instant.now().plus(properties.logoutStateTtl()));

        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(endpoint)
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/logged-out")
    ResponseEntity<Void> loggedOut(
            @RequestParam(value = "state", required = false) String returnedState,
            HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null
                || !validState(
                        session.getAttribute(LOGOUT_STATE),
                        session.getAttribute(LOGOUT_STATE_EXPIRES_AT),
                        returnedState)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        session.invalidate();
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(properties.logoutSuccessUri()))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private String resolvePostLogoutRedirectUri(HttpServletRequest request) {
        String baseUrl =
                UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
                        .replacePath(request.getContextPath())
                        .replaceQuery(null)
                        .fragment(null)
                        .build()
                        .toUriString();
        return properties.postLogoutRedirectUri().replace("{baseUrl}", baseUrl);
    }

    private String newState() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean validState(Object expected, Object expiresAt, String returned) {
        if (!(expected instanceof String expectedState)
                || !(expiresAt instanceof Instant expiry)
                || returned == null
                || !Instant.now().isBefore(expiry)) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedState.getBytes(StandardCharsets.UTF_8),
                returned.getBytes(StandardCharsets.UTF_8));
    }
}
