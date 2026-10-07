package org.las.sso.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.maintenance.AdminBootstrapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AdminAuditQueryServiceTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService registrationService;

    @Autowired AdminBootstrapService adminBootstrapService;

    @Autowired AdminRecoveryCredentialService recoveryCredentialService;

    @Autowired AdminUserManagementService adminUserManagement;

    @Autowired AdminAuditQueryService auditQueryService;

    @Autowired JdbcClient jdbcClient;

    @Autowired MockMvc mockMvc;

    @Test
    void auditEventsApplyFiltersWhitelistAndMaskIp() {
        UUID adminId = administrator("AuditAdminList");
        var target =
                registrationService.register(
                        "AuditTarget", "Audit Target", "audit target passphrase");
        UUID requestId = UUID.randomUUID();
        recoveryCredentialService.issue(
                adminId, null, target.userId(), "audit fixture issuance", requestId);
        adminUserManagement.freeze(
                adminId, null, target.userId(), "audit fixture freeze", requestId);
        jdbcClient
                .sql(
                        """
                INSERT INTO admin_audit_events
                    (id, occurred_at, actor_user_id, action, target_type, target_id,
                     request_id, source_ip_prefix_hash, result, details_json)
                VALUES (:id, :occurredAt, :actorUserId, 'UNKNOWN_ACTION', 'USER', :targetId,
                        :requestId, :ipHash, 'SUCCESS', CAST(:detailsJson AS jsonb))
                """)
                .param("id", UUID.randomUUID())
                .param("occurredAt", OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                .param("actorUserId", adminId)
                .param("targetId", target.userId().toString())
                .param("requestId", requestId)
                .param("ipHash", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
                .param("detailsJson", "{\"secret\":\"must-not-appear\"}")
                .update();

        AdminAuditQueryService.AuditPage page =
                auditQueryService.auditEvents(null, null, null, null, null, 50);

        assertThat(page.items())
                .extracting("action")
                .contains("ADMIN_RECOVERY_CREDENTIAL_ISSUE", "ADMIN_USER_FREEZE", "UNKNOWN_ACTION");
        AdminAuditQueryService.AuditEvent issued =
                page.items().stream()
                        .filter(item -> "ADMIN_RECOVERY_CREDENTIAL_ISSUE".equals(item.action()))
                        .findFirst()
                        .orElseThrow();
        assertThat(issued.details()).containsKeys("previousStatus", "expiresAt");
        AdminAuditQueryService.AuditEvent frozen =
                page.items().stream()
                        .filter(item -> "ADMIN_USER_FREEZE".equals(item.action()))
                        .findFirst()
                        .orElseThrow();
        assertThat(frozen.details())
                .containsEntry("previousStatus", "ACTIVE")
                .containsEntry("nextStatus", "FROZEN");
        AdminAuditQueryService.AuditEvent unknown =
                page.items().stream()
                        .filter(item -> "UNKNOWN_ACTION".equals(item.action()))
                        .findFirst()
                        .orElseThrow();
        assertThat(unknown.details()).isEmpty();
        assertThat(unknown.ipPrefix()).isEqualTo("01020304");

        AdminAuditQueryService.AuditPage filtered =
                auditQueryService.auditEvents(
                        "ADMIN_USER_FREEZE", target.userId().toString(), null, null, null, 50);
        assertThat(filtered.items())
                .hasSize(1)
                .extracting("action")
                .containsExactly("ADMIN_USER_FREEZE");

        AdminAuditQueryService.AuditPage windowed =
                auditQueryService.auditEvents(
                        null,
                        target.userId().toString(),
                        "2026-01-01T00:00:00Z",
                        "2026-01-01T00:00:01Z",
                        null,
                        50);
        assertThat(windowed.items())
                .hasSize(1)
                .extracting("action")
                .containsExactly("UNKNOWN_ACTION");
    }

    @Test
    void auditCursorPaginationIsStableAndOrdered() {
        UUID adminId = administrator("AuditAdminCursor");
        var first =
                registrationService.register(
                        "CursorFirst", "Cursor First", "cursor first passphrase");
        var second =
                registrationService.register(
                        "CursorSecond", "Cursor Second", "cursor second passphrase");
        var third =
                registrationService.register(
                        "CursorThird", "Cursor Third", "cursor third passphrase");
        recoveryCredentialService.issue(
                adminId, null, first.userId(), "cursor issuance one", UUID.randomUUID());
        recoveryCredentialService.issue(
                adminId, null, second.userId(), "cursor issuance two", UUID.randomUUID());
        recoveryCredentialService.issue(
                adminId, null, third.userId(), "cursor issuance three", UUID.randomUUID());

        AdminAuditQueryService.AuditPage pageOne =
                auditQueryService.auditEvents(
                        "ADMIN_RECOVERY_CREDENTIAL_ISSUE", null, null, null, null, 2);
        assertThat(pageOne.items()).hasSize(2);
        assertThat(pageOne.nextCursor()).isNotBlank();

        AdminAuditQueryService.AuditPage pageTwo =
                auditQueryService.auditEvents(
                        "ADMIN_RECOVERY_CREDENTIAL_ISSUE",
                        null,
                        null,
                        null,
                        pageOne.nextCursor(),
                        2);
        assertThat(pageTwo.items()).hasSize(1);
        assertThat(pageTwo.nextCursor()).isNull();
        assertThat(pageOne.items().stream().map(AdminAuditQueryService.AuditEvent::id))
                .doesNotContain(pageTwo.items().getFirst().id());
        assertThat(pageOne.items().getLast().occurredAt())
                .isAfterOrEqualTo(pageTwo.items().getFirst().occurredAt());
    }

    @Test
    void securityEventsFilterByTypeSeverityAndUser() {
        UUID adminId = administrator("AuditAdminSecurity");
        var target =
                registrationService.register(
                        "SecurityTarget", "Security Target", "security target passphrase");
        recoveryCredentialService.issue(
                adminId, null, target.userId(), "security fixture", UUID.randomUUID());
        adminUserManagement.freeze(
                adminId, null, target.userId(), "security fixture freeze", UUID.randomUUID());

        AdminAuditQueryService.SecurityEventPage issued =
                auditQueryService.securityEvents(
                        "ADMIN_RECOVERY_CREDENTIAL_ISSUED",
                        null,
                        target.userId().toString(),
                        null,
                        null,
                        null,
                        50);
        assertThat(issued.items()).hasSize(1).extracting("severity").containsExactly("HIGH");

        AdminAuditQueryService.SecurityEventPage high =
                auditQueryService.securityEvents(
                        null, "HIGH", target.userId().toString(), null, null, null, 50);
        assertThat(high.items())
                .extracting("eventType")
                .containsExactlyInAnyOrder(
                        "ADMIN_RECOVERY_CREDENTIAL_ISSUED", "ACCOUNT_FROZEN_BY_ADMIN");

        AdminAuditQueryService.SecurityEventPage byUser =
                auditQueryService.securityEvents(
                        null, null, target.userId().toString(), null, null, null, 50);
        assertThat(byUser.items()).hasSize(2);
    }

    @Test
    void rejectsMalformedCursorAndTimeRange() {
        assertThatThrownBy(
                        () ->
                                auditQueryService.auditEvents(
                                        null, null, null, null, "not-a-cursor", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                auditQueryService.auditEvents(
                                        null, null, "2026-01-01", null, null, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                auditQueryService.securityEvents(
                                        null, null, "not-a-uuid", null, null, null, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endpointsRequireAdminRoleAndRejectInvalidCursor() throws Exception {
        var actor =
                registrationService.register("AuditActor", "Audit Actor", "audit actor passphrase");
        LasUserPrincipal ordinary = principal(actor.userId(), "AuditActor", false);
        LasUserPrincipal admin = principal(actor.userId(), "AuditActor", true);

        mockMvc.perform(get("/api/v1/admin/audit-events").with(user(ordinary)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/security-events").with(user(ordinary)))
                .andExpect(status().isForbidden());

        var adminSession = new MockHttpSession();
        mockMvc.perform(get("/api/v1/admin/audit-events").session(adminSession).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Cache-Control",
                                        org.hamcrest.Matchers.containsString("no-store")));
        mockMvc.perform(
                        get("/api/v1/admin/audit-events")
                                .session(adminSession)
                                .with(user(admin))
                                .param("cursor", "broken"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/audit").session(adminSession).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .string(org.hamcrest.Matchers.containsString("审计与安全事件")));
    }

    private UUID administrator(String username) {
        jdbcClient.sql("UPDATE users SET is_sso_admin = false WHERE is_sso_admin = true").update();
        var account =
                registrationService.register(username, "Audit Admin", "audit admin passphrase");
        adminBootstrapService.promoteExistingUser(account.userId(), "temporary admin passphrase");
        return account.userId();
    }

    private static LasUserPrincipal principal(UUID userId, String username, boolean ssoAdmin) {
        return new LasUserPrincipal(
                userId,
                username,
                "{noop}irrelevant",
                1,
                ssoAdmin,
                false,
                List.of(new SimpleGrantedAuthority(ssoAdmin ? "ROLE_SSO_ADMIN" : "ROLE_USER")));
    }
}
