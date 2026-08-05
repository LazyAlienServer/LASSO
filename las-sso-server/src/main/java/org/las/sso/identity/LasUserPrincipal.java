package org.las.sso.identity;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class LasUserPrincipal implements UserDetails, AuthenticatedPrincipal {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final String loginUsername;
    private final String passwordHash;
    private final long credentialsVersion;
    private final boolean enabled;
    private final boolean passwordChangeRequired;
    private final List<GrantedAuthority> authorities;

    public LasUserPrincipal(
            UUID userId,
            String loginUsername,
            String passwordHash,
            boolean enabled,
            Collection<? extends GrantedAuthority> authorities) {
        this(userId, loginUsername, passwordHash, 1, enabled, false, authorities);
    }

    public LasUserPrincipal(
            UUID userId,
            String loginUsername,
            String passwordHash,
            boolean enabled,
            boolean passwordChangeRequired,
            Collection<? extends GrantedAuthority> authorities) {
        this(userId, loginUsername, passwordHash, 1, enabled, passwordChangeRequired, authorities);
    }

    public LasUserPrincipal(
            UUID userId,
            String loginUsername,
            String passwordHash,
            long credentialsVersion,
            boolean enabled,
            boolean passwordChangeRequired,
            Collection<? extends GrantedAuthority> authorities) {
        this.userId = Objects.requireNonNull(userId);
        this.loginUsername = Objects.requireNonNull(loginUsername);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        if (credentialsVersion < 1) {
            throw new IllegalArgumentException("credentialsVersion must be positive");
        }
        this.credentialsVersion = credentialsVersion;
        this.enabled = enabled;
        this.passwordChangeRequired = passwordChangeRequired;
        this.authorities = List.copyOf(authorities);
    }

    public UUID userId() {
        return userId;
    }

    public String loginUsername() {
        return loginUsername;
    }

    public boolean passwordChangeRequired() {
        return passwordChangeRequired;
    }

    public long credentialsVersion() {
        return credentialsVersion;
    }

    @Override
    public String getName() {
        return userId.toString();
    }

    /**
     * Spring Security derives Authentication#getName() from this method for UserDetails.
     * The mutable login username is deliberately exposed through loginUsername() instead.
     */
    @Override
    public String getUsername() {
        return getName();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
