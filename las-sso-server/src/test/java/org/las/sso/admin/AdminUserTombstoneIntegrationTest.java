package org.las.sso.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.access.ServiceAccessEligibilityService;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.account.DeletionRequestService;
import org.las.sso.identity.JdbcLasUserDetailsService;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.session.UserSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Real deletion and admin read paths; inbound Servlet sessions are MockMvc fixtures. */
@SpringBootTest(properties = {
        "las.sso.scheduling.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class AdminUserTombstoneIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService registration;
    @Autowired DeletionRequestService deletions;
    @Autowired AdminUserManagementService users;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcLasUserDetailsService userDetails;
    @Autowired UserSessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ServiceAccessEligibilityService eligibility;
    @Autowired PlatformTransactionManager transactions;

    private LasUserPrincipal administrator;
    private MockHttpSession adminSession;

    @BeforeEach
    void administratorSessionFixture() {
        String username = "S5Admin" + UUID.randomUUID().toString().substring(0, 8);
        var account = registration.register(username, "Admin fixture", "s5 administrator password phrase");
        // Role/authentication are fixtures, not acceptance of bootstrap or a browser login.
        jdbc.sql("UPDATE users SET is_sso_admin = false WHERE is_sso_admin").update();
        jdbc.sql("UPDATE users SET is_sso_admin = true WHERE id = :id").param("id", account.userId()).update();
        administrator = (LasUserPrincipal) userDetails.loadUserByUsername(username);
        var request = new MockHttpServletRequest();
        adminSession = (MockHttpSession) request.getSession(true);
        sessions.registerLogin(administrator, request);
    }

    @Test
    void executedDeletionRemainsReadableInAdministratorListAndDetailsWithoutCredentials() {
        var target = deletedAccount();

        assertThat(users.users(target.username(), "DELETED", null, 50).items())
                .extracting(AdminUserManagementService.UserSummary::userId).containsExactly(target.userId());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM password_credentials WHERE user_id = :id")
                .param("id", target.userId()).query(Long.class).single()).isZero();

        var details = users.user(target.userId());

        assertThat(details.userId()).isEqualTo(target.userId());
        assertThat(details.username()).isEqualTo(target.username());
        assertThat(details.displayName()).isEqualTo("已注销用户");
        assertThat(details.status()).isEqualTo("DELETED");
        assertThat(details.credentialState()).isNull();
        assertThat(details.passwordChangedAt()).isNull();
        assertThat(details.lockedUntil()).isNull();
        assertThat(details.activeSessionCount()).isZero();
        assertThat(details.activeAuthorizationCount()).isZero();
    }

    @Test
    void administratorDetailApiReturnsTombstoneWithAbsentCredentialValues() throws Exception {
        var target = deletedAccount();

        String body = mvc.perform(get("/api/v1/admin/users/{id}", target.userId()).session(adminSession).with(user(administrator)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value(target.userId().toString()))
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.credentialState").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.passwordChangedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.lockedUntil").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.activeSessionCount").value(0))
                .andExpect(jsonPath("$.activeAuthorizationCount").value(0))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.recoveryCodes").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var document = json.readTree(body);
        for (String field : List.of("credentialState", "passwordChangedAt", "lockedUntil")) {
            assertThat(document.has(field)).isTrue();
            assertThat(document.get(field).isNull()).isTrue();
        }
    }

    @Test
    void administratorPageAutoSelectsTombstoneAndRendersExplicitAbsentCredentials() throws Exception {
        var target = deletedAccount();

        String html = mvc.perform(get("/admin/users").param("query", target.username()).param("status", "DELETED")
                        .session(adminSession).with(user(administrator)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains(target.username(), target.userId().toString(), "已删除")
                .containsPattern("<dt>凭据状态</dt><dd[^>]*>无凭据</dd>")
                .containsPattern("<dt>密码更新</dt><dd[^>]*>不适用</dd>")
                .containsPattern("<dt>锁定到期</dt><dd[^>]*>不适用</dd>")
                .doesNotContain("data-action=\"", "Deleted account fixture");
    }

    @Test
    void explicitTombstoneSelectionRendersEvenWhenTheListFilterExcludesIt() throws Exception {
        var target = deletedAccount();

        var result = mvc.perform(get("/admin/users").param("query", target.username()).param("status", "ACTIVE")
                        .param("selected", target.userId().toString()).session(adminSession).with(user(administrator)))
                .andExpect(status().isOk()).andReturn();

        assertThat((List<?>) result.getModelAndView().getModel().get("users")).isEmpty();
        assertThat((AdminUserManagementService.UserDetails) result.getModelAndView().getModel().get("selectedUser"))
                .extracting(AdminUserManagementService.UserDetails::userId).isEqualTo(target.userId());
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains(target.username(), "无凭据", "不适用").doesNotContain("data-action=\"");
    }

    @Test
    void unknownIdentityStillReturnsNotFoundRatherThanAnEmptyTombstone() throws Exception {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> users.user(unknown)).isInstanceOf(AdminUserManagementException.class)
                .extracting("code").isEqualTo("ADMIN_TARGET_NOT_FOUND");
        mvc.perform(get("/api/v1/admin/users/{id}", unknown).session(adminSession).with(user(administrator)))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("ADMIN_TARGET_NOT_FOUND"));
    }

    @Test
    void repeatedListDetailAndPageReadsDoNotRecreateCredentialsOrChangeTombstoneState() throws Exception {
        var target = deletedAccount();
        var before = snapshot(target.userId());

        for (int repeat = 0; repeat < 2; repeat++) {
            users.users(target.username(), "DELETED", null, 1);
            users.user(target.userId());
            mvc.perform(get("/api/v1/admin/users/{id}", target.userId()).session(adminSession).with(user(administrator)))
                    .andExpect(status().isOk());
            mvc.perform(get("/admin/users").param("selected", target.userId().toString())
                            .session(adminSession).with(user(administrator)))
                    .andExpect(status().isOk());
        }

        assertThat(snapshot(target.userId())).isEqualTo(before);
        assertThat(eligibility.evaluate(target.userId()).decision()).isEqualTo("DENIED");
        assertThat(eligibility.evaluate(target.userId()).reason()).isEqualTo("ACCOUNT_DELETED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"freeze", "unfreeze", "revoke-sessions", "recovery-credentials"})
    void readableTombstoneStillRejectsAdministratorMutationsWithoutStateChanges(String action) throws Exception {
        var target = deletedAccount();
        var before = snapshot(target.userId());
        adminSession.setAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE, Instant.now());

        mvc.perform(post("/api/v1/admin/users/{id}/{action}", target.userId(), action)
                        .session(adminSession).with(user(administrator)).with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"reason\":\"s5 terminal fixture\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_STATE_CONFLICT"));

        assertThat(snapshot(target.userId())).isEqualTo(before);
    }

    @Test
    void ordinaryAndAnonymousCallersCannotReadAdministratorTombstoneViews() throws Exception {
        var target = deletedAccount();
        var ordinary = registeredAccount("S5Viewer");
        var principal = (LasUserPrincipal) userDetails.loadUserByUsername(ordinary.username());
        for (String path : List.of("/api/v1/admin/users", "/api/v1/admin/users/" + target.userId(),
                "/admin/users?selected=" + target.userId())) {
            mvc.perform(get(path).with(user(principal))).andExpect(status().isForbidden());
            var anonymous = mvc.perform(get(path)).andReturn().getResponse();
            assertThat(anonymous.getStatus()).isIn(401, 302, 403);
            assertThat(anonymous.getContentAsString(StandardCharsets.UTF_8))
                    .doesNotContain(target.userId().toString(), target.username(), "已注销用户");
        }
    }

    @ParameterizedTest
    @EnumSource(CredentialState.class)
    void existingCredentialStatesAndTimestampsStillRenderExactly(CredentialState state) throws Exception {
        var target = registeredAccount("S5Credential");
        jdbc.sql("""
                UPDATE password_credentials SET state = CAST(:state AS credential_state),
                    locked_until = CURRENT_TIMESTAMP + INTERVAL '1 hour'
                 WHERE user_id = :id
                """).param("state", state.name()).param("id", target.userId()).update();
        var expectedPasswordTime = jdbc.sql("SELECT password_changed_at FROM password_credentials WHERE user_id = :id")
                .param("id", target.userId()).query(OffsetDateTime.class).single();
        var expectedLockTime = jdbc.sql("SELECT locked_until FROM password_credentials WHERE user_id = :id")
                .param("id", target.userId()).query(OffsetDateTime.class).single();

        var details = users.user(target.userId());
        assertThat(details.status()).isEqualTo("ACTIVE");
        assertThat(details.credentialState()).isEqualTo(state.name());
        assertThat(details.passwordChangedAt()).isEqualTo(expectedPasswordTime);
        assertThat(details.lockedUntil()).isEqualTo(expectedLockTime);
        mvc.perform(get("/api/v1/admin/users/{id}", target.userId()).session(adminSession).with(user(administrator)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.credentialState").value(state.name()));
        String html = mvc.perform(get("/admin/users").param("selected", target.userId().toString())
                        .session(adminSession).with(user(administrator)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).containsPattern("<dt>凭据状态</dt><dd[^>]*>" + state.name() + "</dd>")
                .doesNotContain("无凭据", "不适用", "未锁定");
    }

    @Test
    void frozenAccountWithCredentialsStillShowsCredentialValuesAndUnfreezeAction() throws Exception {
        var target = registeredAccount("S5Frozen");
        users.freeze(administrator.userId(), null, target.userId(), "fixture review", UUID.randomUUID());

        var details = users.user(target.userId());
        assertThat(details.status()).isEqualTo("FROZEN");
        assertThat(details.frozenAt()).isNotNull();
        assertThat(details.credentialState()).isEqualTo("ACTIVE");
        String html = mvc.perform(get("/admin/users").param("selected", target.userId().toString())
                        .session(adminSession).with(user(administrator)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("已冻结", "未锁定", "data-action=\"unfreeze\"").doesNotContain("无凭据", "不适用");
    }

    @Test
    void mixedAndDeletedFilteredPaginationRetainsAllTombstones() throws Exception {
        String prefix = "S5Page" + UUID.randomUUID().toString().substring(0, 6);
        var deletedIds = new ArrayList<UUID>();
        var allIds = new ArrayList<UUID>();
        for (int index = 0; index < 5; index++) {
            var target = registeredAccount(prefix);
            allIds.add(target.userId());
            if (index % 2 == 0) {
                delete(target);
                deletedIds.add(target.userId());
            }
        }
        for (String filter : List.of("", "DELETED")) {
            var seen = new ArrayList<UUID>();
            UUID cursor = null;
            do {
                var page = users.users(prefix, filter, cursor, 2);
                assertThat(page.items()).hasSizeLessThanOrEqualTo(2);
                for (var item : page.items()) {
                    assertThat(users.user(item.userId()).status()).isEqualTo(item.status());
                    seen.add(item.userId());
                }
                cursor = page.nextCursor() == null ? null : UUID.fromString(page.nextCursor());
                assertThat(seen).doesNotHaveDuplicates();
            } while (cursor != null);
            assertThat(seen).containsExactlyInAnyOrderElementsOf(filter.isEmpty() ? allIds : deletedIds);
        }
        mvc.perform(get("/api/v1/admin/users").param("query", prefix).param("status", "DELETED")
                        .session(adminSession).with(user(administrator)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void missingCredentialFixtureIsInspectableButDoesNotGrantServiceAccessOrLogin() throws Exception {
        var target = registeredAccount("S5Missing");
        // Deliberately inconsistent nonterminal fixture; inspection must not invent ACTIVE credentials.
        jdbc.sql("DELETE FROM password_credentials WHERE user_id = :id").param("id", target.userId()).update();

        assertThat(users.user(target.userId()).status()).isEqualTo("ACTIVE");
        assertThat(users.user(target.userId()).credentialState()).isNull();
        assertThat(eligibility.evaluate(target.userId()).decision()).isEqualTo("DENIED");
        assertThat(eligibility.evaluate(target.userId()).reason()).isEqualTo("ACCOUNT_STATE_INVALID");
        assertThatThrownBy(() -> userDetails.loadUserByUsername(target.username()))
                .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
        String html = mvc.perform(get("/admin/users").param("selected", target.userId().toString())
                        .session(adminSession).with(user(administrator)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("无凭据", "不适用").doesNotContain("未锁定");
    }

    @Test
    void readDuringUncommittedDeletionSeesPriorStateAndAfterCommitSeesTombstone() throws Exception {
        var target = registeredAccount("S5Atomic");
        UUID requestId = dueDeletion(target);
        var before = users.user(target.userId());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                deletions.execute(requestId, target.userId());
                try {
                    var concurrentRead = executor.submit(() -> users.user(target.userId())).get(5, TimeUnit.SECONDS);
                    assertThat(concurrentRead).isEqualTo(before);
                } catch (Exception exception) {
                    throw new AssertionError("Committed admin read should not wait for the deletion writer", exception);
                }
            });
        }
        assertThat(users.user(target.userId()).status()).isEqualTo("DELETED");
        assertThat(users.user(target.userId()).credentialState()).isNull();
    }

    private Target deletedAccount() {
        var target = registeredAccount("S5Deleted");
        delete(target);
        return target;
    }

    private Target registeredAccount(String prefix) {
        String username = prefix + UUID.randomUUID().toString().substring(0, 8);
        var account = registration.register(username, "Deleted account fixture", "s5 initial owner password phrase");
        return new Target(account.userId(), username);
    }

    private void delete(Target target) {
        deletions.execute(dueDeletion(target), target.userId());
    }

    private UUID dueDeletion(Target target) {
        deletions.request(target.userId(), UUID.randomUUID());
        jdbc.sql("""
                UPDATE account_deletion_requests
                   SET requested_at = CURRENT_TIMESTAMP - INTERVAL '31 days',
                       execute_after = CURRENT_TIMESTAMP - INTERVAL '1 day'
                 WHERE user_id = :id AND status = 'PENDING'
                """).param("id", target.userId()).update();
        return jdbc.sql("SELECT id FROM account_deletion_requests WHERE user_id = :id AND status = 'PENDING'")
                .param("id", target.userId()).query(UUID.class).single();
    }

    private List<List<String>> snapshot(UUID id) {
        var result = new ArrayList<List<String>>();
        for (String table : List.of("users", "usernames", "password_credentials", "account_deletion_requests",
                "recovery_codes", "recovery_sessions", "admin_recovery_credentials", "user_sessions",
                "oauth2_authorization", "security_events", "admin_audit_events", "outbox_events")) {
            String column = switch (table) {
                case "users" -> "id";
                case "admin_recovery_credentials" -> "target_user_id";
                case "oauth2_authorization" -> "principal_name";
                case "admin_audit_events" -> "target_id";
                case "outbox_events" -> "aggregate_id";
                default -> "user_id";
            };
            result.add(jdbc.sql("SELECT to_jsonb(t)::text FROM " + table + " t WHERE " + column
                    + "::text = :id ORDER BY to_jsonb(t)::text").param("id", id.toString()).query(String.class).list());
        }
        return result;
    }

    private enum CredentialState { ACTIVE, PASSWORD_CHANGE_REQUIRED, DISABLED }
    private record Target(UUID userId, String username) { }
}
