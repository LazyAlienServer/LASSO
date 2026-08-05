package org.las.sso.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public final class JdbcLasUserDetailsService implements UserDetailsService {

    private static final String FIND_BY_CURRENT_USERNAME = """
            SELECT u.id,
                   un.username,
                   pc.password_hash,
                   pc.credentials_version,
                   u.account_status,
                   pc.state AS credential_state,
                   pc.temporary_expires_at,
                   pc.locked_until,
                   u.is_sso_admin
              FROM users u
              JOIN usernames un ON un.user_id = u.id AND un.valid_to IS NULL
              JOIN password_credentials pc ON pc.user_id = u.id
             WHERE un.username = :username COLLATE "C"
            """;

    private final JdbcClient jdbcClient;

    public JdbcLasUserDetailsService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return jdbcClient.sql(FIND_BY_CURRENT_USERNAME)
                .param("username", username)
                .query((rs, rowNum) -> {
                    boolean active = "ACTIVE".equals(rs.getString("account_status"));
                    String credentialState = rs.getString("credential_state");
                    boolean passwordChangeRequired = "PASSWORD_CHANGE_REQUIRED".equals(credentialState);
                    var temporaryExpiresAt = rs.getObject("temporary_expires_at", java.time.OffsetDateTime.class);
                    var lockedUntil = rs.getObject("locked_until", java.time.OffsetDateTime.class);
                    boolean credentialActive = "ACTIVE".equals(credentialState)
                            || (passwordChangeRequired
                            && temporaryExpiresAt != null
                            && temporaryExpiresAt.isAfter(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)));
                    boolean admin = rs.getBoolean("is_sso_admin");
                    var authorities = admin
                            ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_SSO_ADMIN"))
                            : List.of(new SimpleGrantedAuthority("ROLE_USER"));
                    return new LasUserPrincipal(
                            rs.getObject("id", UUID.class),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getLong("credentials_version"),
                            active && credentialActive
                                    && (lockedUntil == null || !lockedUntil.isAfter(
                                    java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC))),
                            passwordChangeRequired,
                            authorities);
                })
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));
    }
}
