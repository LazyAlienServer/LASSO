package org.las.sso.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.las.sso.identity.JdbcLasUserDetailsService;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.session.UserSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public final class RecentAuthenticationService {

    public static final String SESSION_ATTRIBUTE =
            RecentAuthenticationService.class.getName() + ".LAST_PASSWORD_AUTH_AT";
    private static final Duration FRESHNESS = Duration.ofMinutes(15);

    private final JdbcLasUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionService userSessions;
    private final Clock clock;

    @Autowired
    public RecentAuthenticationService(
            JdbcLasUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            UserSessionService userSessions) {
        this(userDetailsService, passwordEncoder, userSessions, Clock.systemUTC());
    }

    RecentAuthenticationService(
            JdbcLasUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            UserSessionService userSessions,
            Clock clock) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.userSessions = userSessions;
        this.clock = clock;
    }

    public void markPasswordAuthenticated(HttpServletRequest request) {
        request.getSession(true).setAttribute(SESSION_ATTRIBUTE, clock.instant());
    }

    public boolean isRecent(HttpServletRequest request) {
        return passwordAuthenticatedAt(request)
                .filter(at -> at.plus(FRESHNESS).isAfter(clock.instant())).isPresent();
    }

    public Optional<Instant> passwordAuthenticatedAt(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(SESSION_ATTRIBUTE) instanceof Instant authenticatedAt)) {
            return Optional.empty();
        }
        return authenticatedAt.isAfter(clock.instant()) ? Optional.empty() : Optional.of(authenticatedAt);
    }

    public void reauthenticate(
            LasUserPrincipal principal,
            String password,
            HttpServletRequest request) {
        if (principal.passwordChangeRequired()) {
            throw new RecentAuthenticationException("PASSWORD_CHANGE_REQUIRED");
        }
        LasUserPrincipal current;
        try {
            current = (LasUserPrincipal) userDetailsService.loadUserByUsername(principal.loginUsername());
        } catch (UsernameNotFoundException exception) {
            throw new RecentAuthenticationException("REAUTHENTICATION_FAILED");
        }
        if (!current.userId().equals(principal.userId())
                || !current.isEnabled()
                || current.credentialsVersion() != principal.credentialsVersion()
                || current.passwordChangeRequired()
                || password == null
                || !passwordEncoder.matches(password, current.getPassword())) {
            throw new RecentAuthenticationException("REAUTHENTICATION_FAILED");
        }
        request.getSession(true);
        request.changeSessionId();
        markPasswordAuthenticated(request);
        userSessions.updateRecentPasswordAuthentication(request);
    }
}
