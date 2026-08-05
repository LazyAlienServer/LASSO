package org.las.sso.session;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.SameOriginVerifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class UserSessionController {

    private final UserSessionService sessions;
    private final SameOriginVerifier sameOriginVerifier;

    public UserSessionController(UserSessionService sessions, SameOriginVerifier sameOriginVerifier) {
        this.sessions = sessions;
        this.sameOriginVerifier = sameOriginVerifier;
    }

    @GetMapping("/sessions")
    ResponseEntity<List<UserSessionService.SessionView>> sessions(
            Authentication authentication,
            HttpServletRequest request) {
        LasUserPrincipal principal = principal(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sessions.sessions(principal.userId(), request));
    }

    @DeleteMapping("/sessions/{sid}")
    ResponseEntity<Void> revoke(
            @PathVariable String sid,
            Authentication authentication,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        LasUserPrincipal principal = principal(authentication);
        UserSessionService.RevocationResult result = sessions.revoke(principal.userId(), sid, request);
        if (result == UserSessionService.RevocationResult.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        if (result == UserSessionService.RevocationResult.CURRENT) {
            invalidate(request);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        sameOriginVerifier.verify(request);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        LasUserPrincipal principal = principal(authentication);
        sessions.logoutAll(principal.userId(), "USER_GLOBAL_LOGOUT");
        invalidate(request);
        return ResponseEntity.noContent().build();
    }

    private static void invalidate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private static LasUserPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)
                || principal.passwordChangeRequired()) {
            throw new IllegalStateException("Authenticated LAS principal required");
        }
        return principal;
    }
}
