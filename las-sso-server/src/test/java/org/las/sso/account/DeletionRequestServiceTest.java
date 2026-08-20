package org.las.sso.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.las.sso.admin.AdminDeletionRequestService;
import org.las.sso.admin.AdminUserManagementException;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.maintenance.AdminBootstrapService;
import org.las.sso.maintenance.DeletionExecutionJob;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.session.UserSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DeletionRequestServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired
    AccountRegistrationService registrationService;

    @Autowired
    AdminBootstrapService adminBootstrapService;

    @Autowired
    DeletionRequestService deletions;

    @Autowired
    AdminDeletionRequestService adminDeletions;

    @Autowired
    DeletionExecutionJob deletionJob;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserSessionService userSessions;

    @Test
    void requestCreatesPendingRequestWithGlobalLogoutAndSecurityEvent() throws Exception {
        var account = registrationService.register(
                "DelRequestUser", "Del Request User", "del request passphrase");
        var targetSession = login("DelRequestUser", "del request passphrase");
        String sid = (String) targetSession.getAttribute(
                org.las.sso.session.UserSessionService.SID_SESSION_ATTRIBUTE);

        DeletionRequestService.RequestView view = deletions.request(
                account.userId(), UUID.randomUUID());

        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.executeAfter()).isAfter(
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(29));
        assertThat(jdbcClient.sql("""
                SELECT status::text FROM user_sessions WHERE sid = :sid
                """).param("sid", sid).query(String.class).single()).isEqualTo("REVOKED");
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId AND event_type = 'DELETION_REQUESTED'
                """).param("userId", account.userId()).query(Long.class).single()).isOne();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_id = :userId AND event_type = 'GLOBAL_LOGOUT_REQUESTED'
                """).param("userId", account.userId().toString())
                .query(Long.class).single()).isOne();
    }

    @Test
    void cancelRestoresAccountForPendingAndHeldRequests() {
        var account = registrationService.register(
                "DelCancelUser", "Del Cancel User", "del cancel passphrase");
        deletions.request(account.userId(), UUID.randomUUID());

        deletions.cancel(account.userId(), UUID.randomUUID());
        assertThat(deletions.status(account.userId()).status()).isEqualTo("CANCELLED");

        deletions.request(account.userId(), UUID.randomUUID());
        UUID requestId = jdbcClient.sql("""
                SELECT id FROM account_deletion_requests
                 WHERE user_id = :userId AND status = 'PENDING'
                """).param("userId", account.userId()).query(UUID.class).single();
        UUID actorId = administrator("DelSvcAdminCancel");
        adminDeletions.hold(actorId, null, requestId, "dispute", "正在核实申请",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(10), UUID.randomUUID());
        deletions.cancel(account.userId(), UUID.randomUUID());
        assertThat(deletions.status(account.userId()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void secondRequestWhilePendingIsRejected() {
        var account = registrationService.register(
                "DelConflictUser", "Del Conflict User", "del conflict passphrase");
        deletions.request(account.userId(), UUID.randomUUID());
        assertThatThrownBy(() -> deletions.request(account.userId(), UUID.randomUUID()))
                .isInstanceOf(AccountDeletionConflictException.class);
    }

    @Test
    void executionDeletesCredentialsAndMarksAccountDeleted() {
        var account = registrationService.register(
                "DelExecuteUser", "Del Execute User", "del execute passphrase");
        deletions.request(account.userId(), UUID.randomUUID());
        backdateExecution(account.userId());

        int executed = deletions.executeDue(100);

        assertThat(executed).isGreaterThanOrEqualTo(1);
        assertThat(jdbcClient.sql("""
                SELECT account_status::text FROM users WHERE id = :userId
                """).param("userId", account.userId()).query(String.class).single())
                .isEqualTo("DELETED");
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM password_credentials WHERE user_id = :userId
                """).param("userId", account.userId()).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_codes WHERE user_id = :userId
                """).param("userId", account.userId()).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("""
                SELECT status::text FROM account_deletion_requests WHERE user_id = :userId
                """).param("userId", account.userId()).query(String.class).single())
                .isEqualTo("EXECUTED");
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId AND event_type = 'ACCOUNT_DELETED'
                """).param("userId", account.userId()).query(Long.class).single()).isOne();
    }

    @Test
    void heldRequestSurvivesUntilHoldExpiresThenExecutes() {
        var account = registrationService.register(
                "DelHoldUser", "Del Hold User", "del hold passphrase");
        deletions.request(account.userId(), UUID.randomUUID());
        UUID requestId = jdbcClient.sql("""
                SELECT id FROM account_deletion_requests
                 WHERE user_id = :userId AND status = 'PENDING'
                """).param("userId", account.userId()).query(UUID.class).single();
        backdateExecution(account.userId());
        UUID actorId = administrator("DelSvcAdminHold");
        adminDeletions.hold(actorId, null, requestId, "ownership dispute", "正在核实账号状态",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(10), UUID.randomUUID());

        deletions.executeDue(100);
        assertThat(deletions.status(account.userId()).status()).isEqualTo("HELD");
        assertThat(deletions.status(account.userId()).holdPublicSummary()).isEqualTo("正在核实账号状态");

        deletionJob.releaseExpiredHolds(OffsetDateTime.now(ZoneOffset.UTC).plusDays(11));
        assertThat(deletions.status(account.userId()).status()).isEqualTo("PENDING");
        assertThat(deletions.executeDue(100)).isOne();
        assertThat(jdbcClient.sql("""
                SELECT account_status::text FROM users WHERE id = :userId
                """).param("userId", account.userId()).query(String.class).single())
                .isEqualTo("DELETED");
    }

    @Test
    void userCanCancelHeldRequest() {
        var account = registrationService.register(
                "DelCancelHoldUser", "Del Cancel Hold User", "del cancel hold passphrase");
        deletions.request(account.userId(), UUID.randomUUID());
        UUID requestId = jdbcClient.sql("""
                SELECT id FROM account_deletion_requests
                 WHERE user_id = :userId AND status = 'PENDING'
                """).param("userId", account.userId()).query(UUID.class).single();
        UUID actorId = administrator("DelSvcAdminCancelHold");
        adminDeletions.hold(actorId, null, requestId, "ownership dispute", "正在核实账号状态",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(10), UUID.randomUUID());

        deletions.cancel(account.userId(), UUID.randomUUID());

        assertThat(deletions.status(account.userId()).status()).isEqualTo("CANCELLED");
        assertThat(jdbcClient.sql("""
                SELECT hold_until IS NULL AND held_by_user_id IS NULL
                       AND hold_reason IS NULL AND hold_public_summary IS NULL
                  FROM account_deletion_requests WHERE id = :requestId
                """).param("requestId", requestId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void waitingPeriodRestrictsPagesToDeletionScope() throws Exception {
        var account = registrationService.register(
                "DelScopeUser", "Del Scope User", "del scope passphrase");
        var targetSession = login("DelScopeUser", "del scope passphrase");
        deletions.request(account.userId(), UUID.randomUUID());
        targetSession = login("DelScopeUser", "del scope passphrase");

        mockMvc.perform(get("/account/delete").session(targetSession)
                        .with(user(lasPrincipal(account.userId(), "DelScopeUser"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/account").session(targetSession)
                        .with(user(lasPrincipal(account.userId(), "DelScopeUser"))))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/api/v1/me/recovery-codes/status").session(targetSession)
                        .with(user(lasPrincipal(account.userId(), "DelScopeUser"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/me/deletion-request").session(targetSession)
                        .with(user(lasPrincipal(account.userId(), "DelScopeUser"))).with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"del scope passphrase\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void adminHoldValidatesDeadlineAndRequiresRecentAuthentication() throws Exception {
        var account = registrationService.register(
                "DelAdminUser", "Del Admin User", "del admin passphrase");
        deletions.request(account.userId(), UUID.randomUUID());
        UUID requestId = jdbcClient.sql("""
                SELECT id FROM account_deletion_requests WHERE user_id = :userId
                """).param("userId", account.userId()).query(UUID.class).single();
        var actor = registrationService.register(
                "DelActorUser", "Del Actor User", "del actor passphrase");
        LasUserPrincipal admin = lasPrincipal(actor.userId(), "DelActorUser");
        jdbcClient.sql("UPDATE users SET is_sso_admin = false WHERE is_sso_admin = true").update();
        jdbcClient.sql("UPDATE users SET is_sso_admin = true WHERE id = :userId")
                .param("userId", actor.userId()).update();

        var loginRequest = new MockHttpServletRequest();
        var adminSession = (MockHttpSession) loginRequest.getSession(true);
        userSessions.registerLogin(admin, loginRequest);
        mockMvc.perform(post("/api/v1/admin/deletion-requests/{id}/hold", requestId)
                        .session(adminSession).with(user(admin)).with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"dispute\",\"publicSummary\":\"正在核实账号状态\",\"holdUntil\":\"" +
                                OffsetDateTime.now(ZoneOffset.UTC).plusDays(10) + "\"}"))
                .andExpect(status().isForbidden());

        adminSession.setAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE, Instant.now());
        mockMvc.perform(post("/api/v1/admin/deletion-requests/{id}/hold", requestId)
                        .session(adminSession).with(user(admin)).with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"dispute\",\"publicSummary\":\"正在核实账号状态\",\"holdUntil\":\"" +
                                OffsetDateTime.now(ZoneOffset.UTC).plusDays(200) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/deletion-requests/{id}/hold", requestId)
                        .session(adminSession).with(user(admin)).with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"dispute\",\"publicSummary\":\"正在核实账号状态\",\"holdUntil\":\"" +
                                OffsetDateTime.now(ZoneOffset.UTC).plusDays(10) + "\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/admin/deletion-requests/{id}/release", requestId)
                        .session(adminSession).with(user(admin)).with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"resolved\"}"))
                .andExpect(status().isNoContent());
        assertThat(deletions.status(account.userId()).status()).isEqualTo("PENDING");
    }

    @Test
    void adminCannotApplyForSelfDeletion() {
        UUID adminId = administrator("DelAdminSelf");
        assertThatThrownBy(() -> deletions.request(adminId, UUID.randomUUID()))
                .isInstanceOf(AccountDeletionConflictException.class)
                .extracting("code").isEqualTo("ADMIN_DELETION_FORBIDDEN");
    }

    @Test
    void executionSkipsRequestWhenUserBecameAdministrator() {
        var account = registrationService.register(
                "DelPromotedUser", "Del Promoted User", "del promoted passphrase");
        deletions.request(account.userId(), UUID.randomUUID());
        backdateExecution(account.userId());
        jdbcClient.sql("UPDATE users SET is_sso_admin = false WHERE is_sso_admin = true")
                .update();
        adminBootstrapService.promoteExistingUser(account.userId(),
                "temporary admin passphrase");

        deletions.executeDue(100);
        assertThat(jdbcClient.sql("""
                SELECT account_status::text FROM users WHERE id = :userId
                """).param("userId", account.userId()).query(String.class).single())
                .isEqualTo("ACTIVE");
        assertThat(deletions.status(account.userId()).status()).isEqualTo("CANCELLED");
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId AND event_type = 'DELETION_SKIPPED_ADMIN_PROMOTED'
                """).param("userId", account.userId()).query(Long.class).single()).isOne();
    }

    private LasUserPrincipal lasPrincipal(UUID userId, String username) {
        return new LasUserPrincipal(
                userId, username, "{noop}irrelevant", 1, true, false,
                List.of(new SimpleGrantedAuthority("ROLE_SSO_ADMIN")));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        var session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .session(session).with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    private void backdateExecution(UUID userId) {
        jdbcClient.sql("""
                UPDATE account_deletion_requests
                   SET requested_at = :requested, execute_after = :executeAfter
                 WHERE user_id = :userId AND status = 'PENDING'
                """).param("requested", OffsetDateTime.now(ZoneOffset.UTC)
                        .minusDays(30).minusMinutes(5))
                .param("executeAfter", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5))
                .param("userId", userId).update();
    }

    private UUID administrator(String username) {
        jdbcClient.sql("UPDATE users SET is_sso_admin = false WHERE is_sso_admin = true")
                .update();
        var account = registrationService.register(
                username, "Del Svc Admin", "del svc admin passphrase");
        adminBootstrapService.promoteExistingUser(account.userId(),
                "temporary admin passphrase");
        return account.userId();
    }
}
