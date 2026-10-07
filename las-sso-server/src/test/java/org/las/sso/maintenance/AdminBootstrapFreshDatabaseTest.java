package org.las.sso.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.las.sso.identity.JdbcLasUserDetailsService;
import org.las.sso.identity.LasUserPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AdminBootstrapFreshDatabaseTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AdminBootstrapService bootstrapService;

    @Autowired JdbcLasUserDetailsService userDetailsService;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void createsOnlyFirstUserAsAdministratorWithExpiringTemporaryPasswordAndAudit() {
        var result =
                bootstrapService.createFirstUser(
                        "InitialAdmin",
                        "Initial Administrator",
                        "initial administrator passphrase");

        assertThat(result.userId().version()).isEqualTo(7);
        assertThat(result.temporaryPasswordExpiresAt())
                .isAfter(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusHours(23));
        var principal = (LasUserPrincipal) userDetailsService.loadUserByUsername("InitialAdmin");
        assertThat(principal.passwordChangeRequired()).isTrue();
        assertThat(principal.getAuthorities()).extracting("authority").contains("ROLE_SSO_ADMIN");
        assertThat(
                        passwordEncoder.matches(
                                "initial administrator passphrase", principal.getPassword()))
                .isTrue();
        Long auditCount =
                jdbcClient
                        .sql(
                                """
                SELECT COUNT(*) FROM admin_audit_events WHERE id = :auditId
                """)
                        .param("auditId", result.auditEventId())
                        .query(Long.class)
                        .single();
        assertThat(auditCount).isOne();
    }
}
