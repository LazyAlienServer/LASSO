package org.las.sso.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class LasUserPrincipalTest {

    @Test
    void springAuthenticationNameIsImmutableUserIdRatherThanMutableLoginUsername() {
        UUID userId = UUID.fromString("019c10a5-b800-7000-8000-000000000001");
        var principal = new LasUserPrincipal(
                userId,
                "Alice",
                "{noop}irrelevant",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities());

        assertThat(authentication.getName()).isEqualTo(userId.toString());
        assertThat(principal.getName()).isEqualTo(userId.toString());
        assertThat(principal.getUsername()).isEqualTo(userId.toString());
        assertThat(principal.loginUsername()).isEqualTo("Alice");
    }
}
