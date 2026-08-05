package org.las.sso.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.account.PasswordRecoveryService;
import org.las.sso.account.RecoveryFlowException;

import org.las.sso.identity.JdbcLasUserDetailsService;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.maintenance.AdminBootstrapService;
import org.las.sso.security.RecentAuthenticationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AdminRecoveryCredentialServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired
    AccountRegistrationService registrationService;

    @Autowired
    AdminBootstrapService adminBootstrapService;

    @Autowired
    AdminRecoveryCredentialService recoveryCredentialService;

    @Autowired
    AdminUserManagementService adminUserManagement;

    @Autowired
    PasswordRecoveryService passwordRecoveryService;

    @Autowired
    JdbcLasUserDetailsService userDetailsService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    MockMvc mockMvc;

    @Test
    void issuesSingleUseCredentialWithHashExpiryAndLinkedAuditTrail() {
        UUID adminId = administrator("SvcAdminIssue");
        var target = registrationService.register(
                "IssueTarget", "Issue Target", "issue target passphrase");
        UUID requestId = UUID.randomUUID();

        AdminRecoveryCredentialService.IssuedCredential issued =
                recoveryCredentialService.issue(adminId, null, target.userId(),
                        "identity verified through LASLOG history", requestId);

        assertThat(issued.credential()).startsWith("LAS-ADMIN-");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(issued.expiresAt()).isAfter(now.plusMinutes(29)).isBefore(now.plusMinutes(31));

        byte[] storedHash = jdbcClient.sql("""
                SELECT credential_hash FROM admin_recovery_credentials
                 WHERE target_user_id = :targetUserId
                """).param("targetUserId", target.userId()).query(byte[].class).single();
        assertThat(storedHash).hasSize(32);
        assertThat(storedHash)
                .isNotEqualTo(issued.credential().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AuditRow audit = jdbcClient.sql("""
                SELECT action, actor_user_id, target_id, request_id, result
                  FROM admin_audit_events
                 WHERE action = 'ADMIN_RECOVERY_CREDENTIAL_ISSUE'
                   AND target_id = :targetUserId
                """).param("targetUserId", target.userId().toString()).query((rs, rowNum) -> new AuditRow(
                        rs.getString("action"),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("target_id"),
                        rs.getObject("request_id", UUID.class),
                        rs.getString("result")))
                .single();
        assertThat(audit.actorUserId()).isEqualTo(adminId);
        assertThat(audit.targetId()).isEqualTo(target.userId().toString());
        assertThat(audit.requestId()).isEqualTo(requestId);
        assertThat(audit.result()).isEqualTo("SUCCESS");

        SecurityEventRow event = jdbcClient.sql("""
                SELECT event_type, severity, request_id
                  FROM security_events
                 WHERE event_type = 'ADMIN_RECOVERY_CREDENTIAL_ISSUED'
                   AND user_id = :targetUserId
                """).param("targetUserId", target.userId()).query((rs, rowNum) -> new SecurityEventRow(
                        rs.getString("event_type"),
                        rs.getString("severity"),
                        rs.getObject("request_id", UUID.class)))
                .single();
        assertThat(event.severity()).isEqualTo("HIGH");
        assertThat(event.requestId()).isEqualTo(requestId);
    }

    @Test
    void rejectsSelfUnknownAdminAndNonActiveTargets() {
        UUID adminId = administrator("SvcAdminReject");
        var target = registrationService.register(
                "RejectTarget", "Reject Target", "reject target passphrase");
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> recoveryCredentialService.issue(
                adminId, null, adminId, "self", requestId))
                .isInstanceOf(AdminUserManagementException.class)
                .extracting("code").isEqualTo("ADMIN_SELF_ACTION_FORBIDDEN");

        assertThatThrownBy(() -> recoveryCredentialService.issue(
                adminId, null, UUID.randomUUID(), "missing", requestId))
                .isInstanceOf(AdminUserManagementException.class)
                .extracting("code").isEqualTo("ADMIN_TARGET_NOT_FOUND");

        var promotedAdmin = registrationService.register(
                "SecondAdmin", "Second Admin", "second admin passphrase");
        jdbcClient.sql("UPDATE users SET is_sso_admin = false WHERE id = :userId")
                .param("userId", adminId).update();
        jdbcClient.sql("UPDATE users SET is_sso_admin = true WHERE id = :userId")
                .param("userId", promotedAdmin.userId()).update();
        assertThatThrownBy(() -> recoveryCredentialService.issue(
                adminId, null, promotedAdmin.userId(), "admin target", requestId))
                .isInstanceOf(AdminUserManagementException.class)
                .extracting("code").isEqualTo("ADMIN_TARGET_IS_ADMIN");
        jdbcClient.sql("UPDATE users SET is_sso_admin = false WHERE id = :userId")
                .param("userId", promotedAdmin.userId()).update();
        jdbcClient.sql("UPDATE users SET is_sso_admin = true WHERE id = :userId")
                .param("userId", adminId).update();

        adminUserManagement.freeze(adminId, null, target.userId(), "test freeze", requestId);
        assertThatThrownBy(() -> recoveryCredentialService.issue(
                adminId, null, target.userId(), "frozen target", requestId))
                .isInstanceOf(AdminUserManagementException.class)
                .extracting("code").isEqualTo("ACCOUNT_STATE_CONFLICT");

        jdbcClient.sql("""
                UPDATE users
                   SET account_status = 'DELETED', deleted_at = :now, updated_at = :now
                 WHERE id = :userId
                """).param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .param("userId", target.userId()).update();
        assertThatThrownBy(() -> recoveryCredentialService.issue(
                adminId, null, target.userId(), "deleted target", requestId))
                .isInstanceOf(AdminUserManagementException.class)
                .extracting("code").isEqualTo("ACCOUNT_STATE_CONFLICT");
    }

    @Test
    void reissueRevokesPreviousUnusedCredentials() {
        UUID adminId = administrator("SvcAdminReissue");
        var target = registrationService.register(
                "ReissueTarget", "Reissue Target", "reissue target passphrase");

        var first = recoveryCredentialService.issue(adminId, null, target.userId(),
                "first issuance", UUID.randomUUID());
        recoveryCredentialService.issue(adminId, null, target.userId(),
                "second issuance", UUID.randomUUID());

        Long unused = jdbcClient.sql("""
                SELECT COUNT(*) FROM admin_recovery_credentials
                 WHERE target_user_id = :targetUserId
                   AND used_at IS NULL AND revoked_at IS NULL
                """).param("targetUserId", target.userId()).query(Long.class).single();
        assertThat(unused).isOne();
        Long revoked = jdbcClient.sql("""
                SELECT COUNT(*) FROM admin_recovery_credentials
                 WHERE target_user_id = :targetUserId AND revoked_at IS NOT NULL
                """).param("targetUserId", target.userId()).query(Long.class).single();
        assertThat(revoked).isOne();
        assertThatThrownBy(() -> passwordRecoveryService.start(
                "ReissueTarget", first.credential(),
                PasswordRecoveryService.CredentialType.ADMIN_ISSUED, null,
                "v4/24:0b000002"))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code").isEqualTo("RECOVERY_CREDENTIAL_INVALID");
    }

    @Test
    void issuedCredentialCompletesFullRecoveryFlow() {
        UUID adminId = administrator("SvcAdminFlow");
        var target = registrationService.register(
                "FullFlowTarget", "Full Flow Target", "old flow passphrase");
        AdminRecoveryCredentialService.IssuedCredential issued =
                recoveryCredentialService.issue(adminId, null, target.userId(),
                        "issued for full flow test", UUID.randomUUID());

        PasswordRecoveryService.RecoverySession session = passwordRecoveryService.start(
                "FullFlowTarget", issued.credential(),
                PasswordRecoveryService.CredentialType.ADMIN_ISSUED, null,
                "v4/24:0a000001");
        passwordRecoveryService.complete(session.token(), "brand new recovery passphrase");

        var principal = (LasUserPrincipal) userDetailsService.loadUserByUsername("FullFlowTarget");
        assertThat(passwordEncoder.matches("brand new recovery passphrase",
                principal.getPassword())).isTrue();
        OffsetDateTime usedAt = jdbcClient.sql("""
                SELECT used_at FROM admin_recovery_credentials
                 WHERE target_user_id = :targetUserId
                """).param("targetUserId", target.userId())
                .query(OffsetDateTime.class).single();
        assertThat(usedAt).isNotNull();
        Long consumedSessions = jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_sessions
                 WHERE user_id = :targetUserId AND consumed_at IS NOT NULL
                """).param("targetUserId", target.userId()).query(Long.class).single();
        assertThat(consumedSessions).isOne();
        Long recoveredEvent = jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :targetUserId AND event_type = 'PASSWORD_RECOVERED'
                """).param("targetUserId", target.userId()).query(Long.class).single();
        assertThat(recoveredEvent).isOne();
    }

    @Test
    void endpointRequiresAdminRoleAndRecentAuthenticationAndReturnsOnce() throws Exception {
        var actor = registrationService.register(
                "EndpointActor", "Endpoint Actor", "endpoint actor passphrase");
        var target = registrationService.register(
                "EndpointTarget", "Endpoint Target", "endpoint target passphrase");
        LasUserPrincipal actorPrincipal = principal(actor.userId(), "EndpointActor", false);
        LasUserPrincipal adminPrincipal = principal(actor.userId(), "EndpointActor", true);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/recovery-credentials", target.userId())
                        .with(user(actorPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"no role\"}"))
                .andExpect(status().isForbidden());

        var adminSession = new MockHttpSession();
        mockMvc.perform(post("/api/v1/admin/users/{userId}/recovery-credentials", target.userId())
                        .session(adminSession)
                        .with(user(adminPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"missing recent authentication\"}"))
                .andExpect(status().isForbidden());

        adminSession.setAttribute(
                RecentAuthenticationService.SESSION_ATTRIBUTE, Instant.now());
        mockMvc.perform(post("/api/v1/admin/users/{userId}/recovery-credentials", target.userId())
                        .session(adminSession)
                        .with(user(adminPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"identity verified offline\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.credential").value(org.hamcrest.Matchers.startsWith("LAS-ADMIN-")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/admin/users/{userId}/recovery-credentials", actor.userId())
                        .session(adminSession)
                        .with(user(adminPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"self issue\"}"))
                .andExpect(status().isForbidden());
    }

    private UUID administrator(String username) {
        jdbcClient.sql("UPDATE users SET is_sso_admin = false WHERE is_sso_admin = true")
                .update();
        var account = registrationService.register(
                username, "Service Admin", "service admin passphrase");
        adminBootstrapService.promoteExistingUser(account.userId(),
                "temporary admin passphrase");
        return account.userId();
    }

    private static LasUserPrincipal principal(
            UUID userId, String username, boolean ssoAdmin) {
        return new LasUserPrincipal(
                userId, username, "{noop}irrelevant", 1, ssoAdmin, false,
                List.of(new SimpleGrantedAuthority(ssoAdmin ? "ROLE_SSO_ADMIN" : "ROLE_USER")));
    }

    private record AuditRow(
            String action, UUID actorUserId, String targetId, UUID requestId, String result) {
    }

    private record SecurityEventRow(String eventType, String severity, UUID requestId) {
    }
}
