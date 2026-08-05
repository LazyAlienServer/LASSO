package org.las.sso.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.security.Principal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.Base64;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.altcha.altcha.v2.Altcha;
import org.junit.jupiter.api.Test;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.account.PasswordChangeService;
import org.las.sso.account.PasswordRecoveryService;
import org.las.sso.account.RecoveryFlowException;
import org.las.sso.account.UsernameUnavailableException;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.identity.JdbcLasUserDetailsService;
import org.las.sso.maintenance.AdminBootstrapException;
import org.las.sso.maintenance.AdminBootstrapService;
import org.las.sso.maintenance.AdminPasswordResetService;
import org.las.sso.logout.BackChannelLogoutDelivery;
import org.las.sso.security.LoginAttemptService;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.security.PostgresCaptchaChallengeStore;
import org.las.sso.security.PostgresRateLimiter;
import org.las.sso.security.PostgresTransientStateCleanup;
import org.las.sso.security.TransientSecurityKeyHasher;
import org.las.sso.security.AltchaChallengeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthorizationPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired
    RegisteredClientRepository registeredClients;

    @Autowired
    OAuth2AuthorizationService authorizations;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    JdbcLasUserDetailsService userDetailsService;

    @Autowired
    AccountRegistrationService registrationService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AdminBootstrapService adminBootstrapService;

    @Autowired
    AdminPasswordResetService adminPasswordResetService;

    @Autowired
    PasswordChangeService passwordChangeService;

    @Autowired
    PasswordRecoveryService passwordRecoveryService;

    @Autowired
    LoginAttemptService loginAttemptService;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    BackChannelLogoutDelivery backChannelLogoutDelivery;

    @Autowired
    PostgresRateLimiter rateLimiter;

    @Autowired
    PostgresCaptchaChallengeStore challengeStore;

    @Autowired
    PostgresTransientStateCleanup transientStateCleanup;

    @Autowired
    TransientSecurityKeyHasher transientKeyHasher;

    @Autowired
    AltchaChallengeService altchaChallengeService;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    org.springframework.security.web.savedrequest.RequestCache loginRequestCache;

    @Test
    void persistsAuthorizationWithUuidPrincipalName() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("phase-zero-client")
                .clientSecret("{noop}phase-zero-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://client.example/callback")
                .scope(OidcScopes.OPENID)
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .build();
        registeredClients.save(client);

        String userId = "019c10a5-b800-7000-8000-000000000001";
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(userId)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(java.util.Set.of(OidcScopes.OPENID))
                .build();
        authorizations.save(authorization);

        assertThat(authorizations.findById(authorization.getId()))
                .isNotNull()
                .extracting(OAuth2Authorization::getPrincipalName)
                .isEqualTo(userId);
    }

    @Test
    void persistsAndReloadsLasUserPrincipalForAuthorizationCodeExchange() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("principal-round-trip-client")
                .clientSecret("{noop}principal-round-trip-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/callback")
                .scope(OidcScopes.OPENID)
                .build();
        registeredClients.save(client);

        UUID userId = UUID.fromString("019c10a5-b800-7000-8000-000000000099");
        var principal = new LasUserPrincipal(
                userId,
                "RoundTripUser",
                "{noop}irrelevant",
                3,
                true,
                false,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(userId.toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(java.util.Set.of(OidcScopes.OPENID))
                .attribute(Principal.class.getName(), authentication)
                .build();

        authorizations.save(authorization);

        var restoredAuthentication = authorizations.findById(authorization.getId())
                .getAttribute(Principal.class.getName());
        assertThat(restoredAuthentication).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(((UsernamePasswordAuthenticationToken) restoredAuthentication).getPrincipal())
                .isInstanceOfSatisfying(LasUserPrincipal.class, restored -> {
                    assertThat(restored.userId()).isEqualTo(userId);
                    assertThat(restored.loginUsername()).isEqualTo("RoundTripUser");
                    assertThat(restored.credentialsVersion()).isEqualTo(3);
                });
    }

    @Test
    void loadsUsernameCaseSensitivelyButExposesUuidAsPrincipalName() {
        UUID userId = UUID.fromString("019c10a5-b800-7000-8000-000000000002");
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        OffsetDateTime databaseTime = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbcClient.sql("""
                INSERT INTO users (id, display_name, account_status, created_at, updated_at)
                VALUES (:id, 'Alice', 'ACTIVE', :now, :now)
                """).param("id", userId).param("now", databaseTime).update();
        jdbcClient.sql("""
                INSERT INTO usernames (id, user_id, username, valid_from, changed_by_user_id, change_reason)
                VALUES (:usernameId, :userId, 'Alice', :now, :userId, 'REGISTER')
                """).param("usernameId", UUID.randomUUID()).param("userId", userId).param("now", databaseTime).update();
        jdbcClient.sql("""
                INSERT INTO password_credentials
                    (user_id, password_hash, state, credentials_version, password_changed_at)
                VALUES (:userId, '{noop}irrelevant', 'ACTIVE', 1, :now)
                """).param("userId", userId).param("now", databaseTime).update();

        LasUserPrincipal principal = (LasUserPrincipal) userDetailsService.loadUserByUsername("Alice");

        assertThat(principal.getName()).isEqualTo(userId.toString());
        assertThat(principal.loginUsername()).isEqualTo("Alice");
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("alice"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void registersAccountPasswordAndTenHashedRecoveryCodesAtomically() {
        var result = registrationService.register(
                "CaseSensitiveUser",
                "Case Sensitive User",
                "  correct unique passphrase  ");

        assertThat(result.userId().version()).isEqualTo(7);
        assertThat(result.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        String hash = jdbcClient.sql("""
                SELECT password_hash FROM password_credentials WHERE user_id = :userId
                """).param("userId", result.userId()).query(String.class).single();
        assertThat(hash).startsWith("{argon2id}$argon2id$");
        assertThat(passwordEncoder.matches("  correct unique passphrase  ", hash)).isTrue();
        Long codeCount = jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_codes WHERE user_id = :userId
                """).param("userId", result.userId()).query(Long.class).single();
        assertThat(codeCount).isEqualTo(10);
    }

    @Test
    void duplicateExactUsernameRollsBackWithoutOrphanUser() {
        registrationService.register("DuplicateUser", "First", "first unique passphrase");
        Long usersBefore = jdbcClient.sql("SELECT COUNT(*) FROM users").query(Long.class).single();

        assertThatThrownBy(() -> registrationService.register(
                "DuplicateUser", "Second", "second unique passphrase"))
                .isInstanceOf(UsernameUnavailableException.class);

        Long usersAfter = jdbcClient.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
        assertThat(usersAfter).isEqualTo(usersBefore);
    }

    @Test
    void administratorBootstrapAndDirectOfflinePasswordResetRevokeSecurityState() {
        var account = registrationService.register(
                "BootstrapTarget", "Bootstrap Target", "bootstrap target passphrase");

        var result = adminBootstrapService.promoteExistingUser(
                account.userId(), "temporary administrator passphrase");

        assertThat(result.userId()).isEqualTo(account.userId());
        var principal = (LasUserPrincipal) userDetailsService.loadUserByUsername("BootstrapTarget");
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.passwordChangeRequired()).isTrue();
        assertThat(principal.getAuthorities()).extracting("authority")
                .contains("ROLE_SSO_ADMIN");
        Long activeCodes = jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_codes
                 WHERE user_id = :userId AND revoked_at IS NULL
                """).param("userId", account.userId()).query(Long.class).single();
        assertThat(activeCodes).isZero();
        assertThatThrownBy(() -> adminBootstrapService.promoteExistingUser(
                account.userId(), "another temporary passphrase"))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("ADMIN_ALREADY_EXISTS");

        passwordChangeService.changePassword(
                account.userId(),
                true,
                "temporary administrator passphrase",
                "permanent administrator passphrase");
        var activated = (LasUserPrincipal) userDetailsService.loadUserByUsername("BootstrapTarget");
        assertThat(activated.passwordChangeRequired()).isFalse();
        assertThat(passwordEncoder.matches(
                "permanent administrator passphrase", activated.getPassword())).isTrue();
        Long credentialVersion = jdbcClient.sql("""
                SELECT credentials_version FROM password_credentials WHERE user_id = :userId
                """).param("userId", account.userId()).query(Long.class).single();
        assertThat(credentialVersion).isEqualTo(3);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                INSERT INTO recovery_codes
                    (id, user_id, code_hash, generation, created_at)
                VALUES (:id, :userId, :hash, 99, :now)
                """).param("id", UUID.randomUUID()).param("userId", account.userId())
                .param("hash", new byte[] {1, 2, 3}).param("now", now).update();
        jdbcClient.sql("""
                INSERT INTO recovery_sessions
                    (id, user_id, session_token_hash, credential_type,
                     created_at, expires_at)
                VALUES (:id, :userId, :hash, 'RECOVERY_CODE', :now, :expiresAt)
                """).param("id", UUID.randomUUID()).param("userId", account.userId())
                .param("hash", new byte[] {4, 5, 6}).param("now", now)
                .param("expiresAt", now.plusMinutes(10)).update();
        jdbcClient.sql("""
                INSERT INTO admin_recovery_credentials
                    (id, target_user_id, issued_by_user_id, credential_hash,
                     expires_at, reason, created_at)
                VALUES (:id, :userId, :userId, :hash, :expiresAt,
                        'RESET_TEST', :now)
                """).param("id", UUID.randomUUID()).param("userId", account.userId())
                .param("hash", new byte[] {7, 8, 9})
                .param("expiresAt", now.plusMinutes(10)).param("now", now).update();
        String securityEpoch = jdbcClient.sql("""
                SELECT security_epoch FROM system_security_state WHERE id = 1
                """).query(String.class).single();
        UUID sessionId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO user_sessions
                    (id, sid, user_id, status, remembered, admin_session,
                     credentials_version, security_epoch, created_at, last_seen_at,
                     idle_expires_at, absolute_expires_at, last_password_auth_at)
                VALUES (:id, :sid, :userId, 'ACTIVE', false, true, 3, :epoch,
                        :now, :now, :idleExpiresAt, :absoluteExpiresAt, :now)
                """).param("id", sessionId).param("sid", "admin-reset-test-session")
                .param("userId", account.userId()).param("epoch", securityEpoch)
                .param("now", now).param("idleExpiresAt", now.plusHours(1))
                .param("absoluteExpiresAt", now.plusHours(2)).update();
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("admin-reset-test-client")
                .clientSecret("{noop}admin-reset-test-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://admin-reset.example/callback")
                .scope(OidcScopes.OPENID)
                .build();
        registeredClients.save(client);
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(account.userId().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(java.util.Set.of(OidcScopes.OPENID))
                .build();
        authorizations.save(authorization);

        var administrator = adminPasswordResetService.currentAdministrator();
        assertThat(administrator.userId()).isEqualTo(account.userId());
        assertThat(administrator.username()).isEqualTo("BootstrapTarget");
        var reset = adminPasswordResetService.resetPassword(
                administrator.userId(), "final reset administrator passphrase");

        assertThat(reset.username()).isEqualTo("BootstrapTarget");
        var resetPrincipal = (LasUserPrincipal) userDetailsService.loadUserByUsername("BootstrapTarget");
        assertThat(resetPrincipal.passwordChangeRequired()).isFalse();
        assertThat(passwordEncoder.matches(
                "final reset administrator passphrase", resetPrincipal.getPassword())).isTrue();
        assertThat(passwordEncoder.matches(
                "permanent administrator passphrase", resetPrincipal.getPassword())).isFalse();
        Map<String, Object> credential = jdbcClient.sql("""
                SELECT state::text AS state, credentials_version,
                       temporary_expires_at, failed_attempts, locked_until
                  FROM password_credentials WHERE user_id = :userId
                """).param("userId", account.userId()).query().singleRow();
        assertThat(credential.get("state")).isEqualTo("ACTIVE");
        assertThat(credential.get("credentials_version")).isEqualTo(4L);
        assertThat(credential.get("temporary_expires_at")).isNull();
        assertThat(credential.get("failed_attempts")).isEqualTo(0);
        assertThat(credential.get("locked_until")).isNull();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_codes
                 WHERE user_id = :userId AND revoked_at IS NULL
                """).param("userId", account.userId()).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_sessions
                 WHERE user_id = :userId AND consumed_at IS NULL
                """).param("userId", account.userId()).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM admin_recovery_credentials
                 WHERE target_user_id = :userId AND revoked_at IS NULL
                """).param("userId", account.userId()).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("""
                SELECT status::text FROM user_sessions WHERE id = :id
                """).param("id", sessionId).query(String.class).single()).isEqualTo("REVOKED");
        assertThat(authorizations.findById(authorization.getId())).isNull();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM admin_audit_events
                 WHERE id = :id AND action = 'ADMIN_PASSWORD_RESET_BY_MAINTENANCE'
                """).param("id", reset.auditEventId()).query(Long.class).single()).isOne();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_id = :userId
                   AND event_type = 'GLOBAL_LOGOUT_REQUESTED'
                   AND payload_json ->> 'reason' = 'ADMIN_PASSWORD_RESET'
                """).param("userId", account.userId().toString())
                .query(Long.class).single()).isOne();
    }

    @Test
    void locksNormalAccountAfterTenFailuresAndSuccessClearsCounters() {
        var account = registrationService.register(
                "LoginFailureUser", "Login Failure User", "login failure passphrase");

        for (int attempt = 0; attempt < 10; attempt++) {
            loginAttemptService.recordFailure("LoginFailureUser");
        }

        assertThat(userDetailsService.loadUserByUsername("LoginFailureUser").isEnabled()).isFalse();
        Long lockEvents = jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId AND event_type = 'ACCOUNT_TEMPORARILY_LOCKED'
                """).param("userId", account.userId()).query(Long.class).single();
        assertThat(lockEvents).isOne();
        loginAttemptService.recordSuccess(account.userId());
        assertThat(userDetailsService.loadUserByUsername("LoginFailureUser").isEnabled()).isTrue();
    }

    @Test
    void passwordChangeEndpointChangesActivePasswordAndInvalidatesRecoveryCodes() throws Exception {
        var account = registrationService.register(
                "WebPasswordUser", "Web Password User", "original web passphrase");
        var principal = (LasUserPrincipal) userDetailsService.loadUserByUsername("WebPasswordUser");

        mockMvc.perform(post("/api/v1/me/password-change")
                        .with(user(principal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("""
                                {"currentPassword":"original web passphrase",
                                 "newPassword":"replacement web passphrase"}
                                """))
                .andExpect(status().isNoContent());

        var updated = (LasUserPrincipal) userDetailsService.loadUserByUsername("WebPasswordUser");
        assertThat(passwordEncoder.matches("replacement web passphrase", updated.getPassword())).isTrue();
        Long activeCodes = jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_codes
                 WHERE user_id = :userId AND revoked_at IS NULL
                """).param("userId", account.userId()).query(Long.class).single();
        assertThat(activeCodes).isZero();
    }

    @Test
    void passwordChangeRequiredPrincipalCannotBrowseOtherPages() throws Exception {
        var account = registrationService.register(
                "RestrictedAdmin", "Restricted Admin", "restricted admin passphrase");
        jdbcClient.sql("""
                UPDATE password_credentials
                   SET state = 'PASSWORD_CHANGE_REQUIRED',
                       temporary_expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours'
                 WHERE user_id = :userId
                """).param("userId", account.userId()).update();
        var restricted = (LasUserPrincipal) userDetailsService.loadUserByUsername("RestrictedAdmin");

        mockMvc.perform(get("/").with(user(restricted)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/password-change-required"));
    }

    @Test
    void loginPageRedirectsAuthenticatedUsersToTheCorrectAccountDestination() throws Exception {
        var account = registrationService.register(
                "AlreadySignedInUser", "Already Signed In User", "already signed in passphrase");
        var principal = (LasUserPrincipal) userDetailsService.loadUserByUsername("AlreadySignedInUser");

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/login").with(user(principal)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account"));

        var required = new LasUserPrincipal(
                principal.userId(), principal.loginUsername(), principal.getPassword(), true, true,
                principal.getAuthorities());
        mockMvc.perform(get("/login").with(user(required)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/password-change-required"));
    }

    @Test
    void accountSecurityRedirectsIntoDesignedRecoveryPageAndRequiredChangeRemainsRestricted() throws Exception {
        var account = registrationService.register(
                "SecurityPageUser", "Security Page User", "security page passphrase");
        var principal = (LasUserPrincipal) userDetailsService.loadUserByUsername("SecurityPageUser");

        mockMvc.perform(get("/account/security").with(user(principal)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account/recovery-codes#password-change"));

        var required = new LasUserPrincipal(
                principal.userId(), principal.loginUsername(), principal.getPassword(), true, true,
                principal.getAuthorities());
        mockMvc.perform(get("/password-change-required").with(user(required)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("临时密码")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("rotate-recovery-codes"))));
    }

    @Test
    void accountCenterShowsStableIdentityAndDesignQuickActions() throws Exception {
        var account = registrationService.register(
                "AccountCenterUser", "Account Center User", "account center passphrase");
        var principal = (LasUserPrincipal) userDetailsService.loadUserByUsername("AccountCenterUser");

        mockMvc.perform(get("/").with(user(principal)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account"));

        mockMvc.perform(get("/account").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("个人中心")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString(account.username())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString(account.userId().toString())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("href=\"/account/security\""))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("href=\"/account/sessions\"")));
    }

    @Test
    void passportAccountManagementRoutesRenderTheirRealCapabilityState() throws Exception {
        var account = registrationService.register(
                "PassportPagesUser", "Passport Pages User", "passport pages passphrase");
        var principal = (LasUserPrincipal) userDetailsService.loadUserByUsername("PassportPagesUser");

        mockMvc.perform(get("/account/sessions").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("退出所有设备")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("aria-current=\"page\"")));

        mockMvc.perform(get("/account/recovery-codes").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("recovery-codes.js")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("rotate-recovery-codes")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("password-change-dialog")));

        mockMvc.perform(get("/account/authorizations").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("已授权应用")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("真实授权关系会显示在这里")));

        mockMvc.perform(get("/account/delete").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("30 天等待期")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("注销申请暂未开放")));
    }

    @Test
    void userCanListAndRevokeOnlyTheirOwnClientAuthorization() throws Exception {
        RefreshFixture target = createRefreshFixture("AuthorizationRevokeUser");
        RefreshFixture other = createRefreshFixture("AuthorizationOtherUser");
        var targetPrincipal = (LasUserPrincipal) userDetailsService
                .loadUserByUsername("AuthorizationRevokeUser");
        var otherPrincipal = (LasUserPrincipal) userDetailsService
                .loadUserByUsername("AuthorizationOtherUser");

        jdbcClient.sql("""
                INSERT INTO oauth2_authorization_consent
                    (registered_client_id, principal_name, authorities)
                SELECT registered_client_id, principal_name, 'SCOPE_profile'
                  FROM oauth2_authorization
                 WHERE id = :authorizationId
                """)
                .param("authorizationId", target.authorizationId())
                .update();

        mockMvc.perform(get("/api/v1/me/authorizations").with(user(targetPrincipal)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].clientId").value(target.clientId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].scopes[0]").value("profile"));

        mockMvc.perform(get("/account/authorizations").with(user(targetPrincipal)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString(
                                "account-authorizations.js")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString(
                                "class=\"authorization-revoke\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString(target.clientId())));

        mockMvc.perform(delete("/api/v1/me/authorizations/{clientId}", target.clientId())
                        .with(user(otherPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000"))
                .andExpect(status().isNoContent());
        assertThat(authorizations.findById(target.authorizationId())).isNotNull();

        mockMvc.perform(delete("/api/v1/me/authorizations/{clientId}", target.clientId())
                        .with(user(targetPrincipal))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("CSRF_INVALID"));

        mockMvc.perform(delete("/api/v1/me/authorizations/{clientId}", target.clientId())
                        .with(user(targetPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000"))
                .andExpect(status().isNoContent());

        assertThat(authorizations.findById(target.authorizationId())).isNull();
        assertThat(authorizations.findById(other.authorizationId())).isNotNull();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM oauth2_authorization_consent
                 WHERE principal_name = :principalName
                """)
                .param("principalName", target.userId().toString())
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_id = :userId
                   AND payload_json ->> 'clientId' = :clientId
                   AND payload_json ->> 'reason' = 'USER_AUTHORIZATION_REVOKED'
                """)
                .param("userId", target.userId().toString())
                .param("clientId", target.clientId())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId
                   AND client_id = :clientId
                   AND event_type = 'AUTHORIZATION_REVOKED'
                """)
                .param("userId", target.userId())
                .param("clientId", target.clientId())
                .query(Long.class).single()).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/me/authorizations/{clientId}", target.clientId())
                        .with(user(targetPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000"))
                .andExpect(status().isNoContent());
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId
                   AND client_id = :clientId
                   AND event_type = 'AUTHORIZATION_REVOKED'
                """)
                .param("userId", target.userId())
                .param("clientId", target.clientId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void recoveryCodeRotationRequiresRecentPasswordAndRevokesOldGeneration() throws Exception {
        var account = registrationService.register(
                "RecoveryRotateUser", "Recovery Rotate User", "recovery rotate passphrase");
        var principal = (LasUserPrincipal) userDetailsService.loadUserByUsername("RecoveryRotateUser");
        var session = new MockHttpSession();
        String originalSessionId = session.getId();

        mockMvc.perform(post("/api/v1/me/recovery-codes/rotate")
                        .with(user(principal))
                        .session(session)
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("RECENT_AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post("/api/v1/me/reauthentication")
                        .with(user(principal))
                        .session(session)
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.41");
                            return request;
                        })
                        .contentType("application/json")
                        .content("{\"password\":\"incorrect reauthentication password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("REAUTHENTICATION_FAILED"));
        assertThat(session.getAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE)).isNull();
        assertThat(session.getId()).isEqualTo(originalSessionId);

        mockMvc.perform(post("/api/v1/me/reauthentication")
                        .with(user(principal))
                        .session(session)
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.41");
                            return request;
                        })
                        .contentType("application/json")
                        .content("{\"password\":\"recovery rotate passphrase\"}"))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .request().sessionAttribute(
                                RecentAuthenticationService.SESSION_ATTRIBUTE,
                                org.hamcrest.Matchers.instanceOf(Instant.class)));
        assertThat(session.getId()).isNotEqualTo(originalSessionId);

        mockMvc.perform(post("/api/v1/me/recovery-codes/rotate")
                        .with(user(principal))
                        .session(session)
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.generation").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.recoveryCodes.length()").value(10));

        Long activeCodes = jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_codes
                 WHERE user_id = :userId AND revoked_at IS NULL AND used_at IS NULL
                """).param("userId", account.userId()).query(Long.class).single();
        Long revokedFirstGeneration = jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_codes
                 WHERE user_id = :userId AND generation = 1 AND revoked_at IS NOT NULL
                """).param("userId", account.userId()).query(Long.class).single();
        assertThat(activeCodes).isEqualTo(10);
        assertThat(revokedFirstGeneration).isEqualTo(10);

        mockMvc.perform(get("/api/v1/me/recovery-codes/status")
                        .with(user(principal)).session(session))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.availableCount").value(10))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.generatedAt").isNotEmpty());
    }

    @Test
    void formLoginUsesGenericFailureAndResetsDatabaseCounterAfterSuccess() throws Exception {
        var account = registrationService.register(
                "FormLoginUser", "Form Login User", "form login passphrase");

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "FormLoginUser")
                        .param("password", "wrong password value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
        Integer failures = jdbcClient.sql("""
                SELECT failed_attempts FROM password_credentials WHERE user_id = :userId
                """).param("userId", account.userId()).query(Integer.class).single();
        assertThat(failures).isEqualTo(1);

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "FormLoginUser")
                        .param("password", "form login passphrase"))
                .andExpect(status().is3xxRedirection());
        failures = jdbcClient.sql("""
                SELECT failed_attempts FROM password_credentials WHERE user_id = :userId
                """).param("userId", account.userId()).query(Integer.class).single();
        assertThat(failures).isZero();
    }

    @Test
    void formLoginRegistersPersistentSessionAndGlobalLogoutRevokesIt() throws Exception {
        var account = registrationService.register(
                "PersistentSessionUser", "Persistent Session User", "persistent session passphrase");
        var session = new MockHttpSession();

        mockMvc.perform(post("/login")
                        .session(session)
                        .with(csrf())
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/140.0")
                        .param("username", "PersistentSessionUser")
                        .param("password", "persistent session passphrase"))
                .andExpect(status().is3xxRedirection());

        String sid = jdbcClient.sql("""
                SELECT sid FROM user_sessions
                 WHERE user_id = :userId AND status = 'ACTIVE'
                """).param("userId", account.userId()).query(String.class).single();
        assertThat(sid).isNotBlank();
        assertThat(session.getAttribute(org.las.sso.session.UserSessionService.SID_SESSION_ATTRIBUTE))
                .isEqualTo(sid);

        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("persistent-session-client-" + UUID.randomUUID())
                .clientSecret("{noop}persistent-session-secret-" + UUID.randomUUID())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/session-callback")
                .scope(OidcScopes.OPENID)
                .build();
        registeredClients.save(client);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                INSERT INTO registered_client_metadata
                    (registered_client_id, client_type, status, config_version,
                     resource_audiences, require_pkce, updated_at)
                VALUES (:id, 'FIRST_PARTY', 'ACTIVE', 1,
                        ARRAY['urn:test:session'], true, :now)
                """).param("id", client.getId()).param("now", now).update();
        jdbcClient.sql("""
                INSERT INTO registered_client_backchannel_logout_uris
                    (registered_client_id, uri, session_required)
                VALUES (:id, 'http://127.0.0.1:65534/internal/backchannel-logout', true)
                """).param("id", client.getId()).update();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(account.userId().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(java.util.Set.of(OidcScopes.OPENID))
                .build();
        var authorizationRequest = new MockHttpServletRequest();
        authorizationRequest.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(authorizationRequest));
        try {
            authorizations.save(authorization);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        assertThat(jdbcClient.sql("""
                SELECT sid FROM oauth2_authorization_security
                 WHERE authorization_id = :authorizationId
                """).param("authorizationId", authorization.getId()).query(String.class).single())
                .isEqualTo(sid);

        mockMvc.perform(get("/api/v1/me/sessions").session(session))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].sid").value(sid))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].current").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].userAgentSummary").value("Chrome · Windows"));

        mockMvc.perform(post("/api/v1/me/logout-all")
                        .session(session)
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNoContent());

        assertThat(jdbcClient.sql("""
                SELECT status::text FROM user_sessions WHERE sid = :sid
                """).param("sid", sid).query(String.class).single()).isEqualTo("REVOKED");
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_id = :userId
                   AND event_type = 'GLOBAL_LOGOUT_REQUESTED'
                   AND payload_json ->> 'reason' = 'USER_GLOBAL_LOGOUT'
                """).param("userId", account.userId().toString()).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_id = :userId
                   AND event_type = 'BACKCHANNEL_LOGOUT_REQUESTED'
                   AND payload_json ->> 'sid' = :sid
                   AND payload_json ->> 'clientId' = :clientId
                """).param("userId", account.userId().toString())
                .param("sid", sid).param("clientId", client.getClientId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void trustedDeviceCreatesSlidingThirtyDaySessionWithNinetyDayAbsoluteLimit() throws Exception {
        var account = registrationService.register(
                "TrustedDeviceUser", "Trusted Device User", "trusted device passphrase");
        var session = new MockHttpSession();

        mockMvc.perform(post("/login")
                        .session(session)
                        .with(csrf())
                        .param("username", "TrustedDeviceUser")
                        .param("password", "trusted device passphrase")
                        .param("rememberDevice", "true"))
                .andExpect(status().is3xxRedirection());

        var persisted = jdbcClient.sql("""
                SELECT remembered, created_at, idle_expires_at, absolute_expires_at
                  FROM user_sessions
                 WHERE user_id = :userId AND status = 'ACTIVE'
                """).param("userId", account.userId())
                .query((rs, rowNum) -> java.util.Map.of(
                        "remembered", rs.getBoolean("remembered"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "idleExpiresAt", rs.getObject("idle_expires_at", OffsetDateTime.class),
                        "absoluteExpiresAt", rs.getObject("absolute_expires_at", OffsetDateTime.class)))
                .single();

        assertThat(persisted.get("remembered")).isEqualTo(true);
        assertThat(java.time.Duration.between(
                (OffsetDateTime) persisted.get("createdAt"),
                (OffsetDateTime) persisted.get("idleExpiresAt"))).isEqualTo(java.time.Duration.ofDays(30));
        assertThat(java.time.Duration.between(
                (OffsetDateTime) persisted.get("createdAt"),
                (OffsetDateTime) persisted.get("absoluteExpiresAt"))).isEqualTo(java.time.Duration.ofDays(90));
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Math.toIntExact(java.time.Duration.ofDays(30).toSeconds()));
    }

    @Test
    void globalLogoutHandlesLegacyAuthorizationWithoutSid() throws Exception {
        var account = registrationService.register(
                "LegacyLogoutUser", "Legacy Logout User", "legacy logout passphrase");
        var session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .session(session)
                        .with(csrf())
                        .param("username", "LegacyLogoutUser")
                        .param("password", "legacy logout passphrase"))
                .andExpect(status().is3xxRedirection());

        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("legacy-logout-client-" + UUID.randomUUID())
                .clientSecret("{noop}legacy-logout-secret-" + UUID.randomUUID())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/legacy-callback")
                .scope(OidcScopes.OPENID)
                .build();
        registeredClients.save(client);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                INSERT INTO registered_client_metadata
                    (registered_client_id, client_type, status, config_version,
                     resource_audiences, require_pkce, updated_at)
                VALUES (:id, 'FIRST_PARTY', 'ACTIVE', 1,
                        ARRAY['urn:test:legacy-logout'], true, :now)
                """).param("id", client.getId()).param("now", now).update();
        jdbcClient.sql("""
                INSERT INTO registered_client_backchannel_logout_uris
                    (registered_client_id, uri, session_required)
                VALUES (:id, 'http://127.0.0.1:65534/internal/backchannel-logout', true)
                """).param("id", client.getId()).update();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(account.userId().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(java.util.Set.of(OidcScopes.OPENID))
                .build();
        authorizations.save(authorization);
        assertThat(jdbcClient.sql("""
                SELECT sid FROM oauth2_authorization_security
                 WHERE authorization_id = :authorizationId
                """).param("authorizationId", authorization.getId())
                .query(String.class).optional()).isEmpty();

        mockMvc.perform(post("/api/v1/me/logout-all")
                        .session(session)
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNoContent());
    }

    @Test
    void userCanRevokeAnotherPersistentSession() throws Exception {
        var account = registrationService.register(
                "RevokeSessionUser", "Revoke Session User", "revoke session passphrase");
        var session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .session(session)
                        .with(csrf())
                        .param("username", "RevokeSessionUser")
                        .param("password", "revoke session passphrase"))
                .andExpect(status().is3xxRedirection());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String otherSid = UUID.randomUUID().toString();
        String epoch = jdbcClient.sql("SELECT security_epoch FROM system_security_state WHERE id = 1")
                .query(String.class).single();
        jdbcClient.sql("""
                INSERT INTO user_sessions
                    (id, sid, user_id, status, remembered, admin_session,
                     credentials_version, security_epoch, created_at, last_seen_at,
                     idle_expires_at, absolute_expires_at, last_password_auth_at,
                     user_agent_summary)
                VALUES (:id, :sid, :userId, 'ACTIVE', false, false, 1, :epoch,
                        :now, :now, :idle, :absolute, :now, 'Firefox · Linux')
                """).param("id", UUID.randomUUID()).param("sid", otherSid)
                .param("userId", account.userId()).param("epoch", epoch)
                .param("now", now).param("idle", now.plusHours(24))
                .param("absolute", now.plusDays(14)).update();

        mockMvc.perform(delete("/api/v1/me/sessions/{sid}", otherSid)
                        .session(session)
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000"))
                .andExpect(status().isNoContent());

        assertThat(jdbcClient.sql("SELECT status::text FROM user_sessions WHERE sid = :sid")
                .param("sid", otherSid).query(String.class).single()).isEqualTo("REVOKED");
        Long active = jdbcClient.sql("""
                SELECT COUNT(*) FROM user_sessions
                 WHERE user_id = :userId AND status = 'ACTIVE'
                """).param("userId", account.userId()).query(Long.class).single();
        assertThat(active).isEqualTo(1);
    }

    @Test
    void backChannelLogoutOutboxIsDeliveredAndMarkedProcessed() throws Exception {
        var receivedBody = new AtomicReference<String>();
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/backchannel", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            UUID eventId = UUID.randomUUID();
            String userId = "019c10a5-b800-7000-8000-000000000099";
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/backchannel";
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbcClient.sql("""
                    INSERT INTO outbox_events
                        (id, aggregate_type, aggregate_id, event_type, payload_json,
                         created_at, available_at)
                    VALUES (:id, 'USER', :userId, 'BACKCHANNEL_LOGOUT_REQUESTED',
                            jsonb_build_object('userId', :userId,
                                               'clientId', 'delivery-test-client',
                                               'uri', :uri,
                                               'sid', 'delivery-test-sid'),
                            :now, :now)
                    """).param("id", eventId).param("userId", userId)
                    .param("uri", uri).param("now", now).update();

            backChannelLogoutDelivery.deliverPending();

            assertThat(receivedBody.get()).startsWith("logout_token=");
            assertThat(jdbcClient.sql("""
                    SELECT processed_at IS NOT NULL FROM outbox_events WHERE id = :id
                    """).param("id", eventId).query(Boolean.class).single()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void browserDevtoolsProbeCannotBecomePostLoginRedirectTarget() throws Exception {
        registrationService.register(
                "DevtoolsProbeUser", "Devtools Probe User", "devtools probe test passphrase");
        var session = new MockHttpSession();

        mockMvc.perform(get("/.well-known/appspecific/com.chrome.devtools.json")
                        .session(session))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/login")
                        .session(session)
                        .with(csrf())
                        .param("username", "DevtoolsProbeUser")
                        .param("password", "devtools probe test passphrase"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void oidcAuthorizationRequestRemainsCacheableForPostLoginReturn() {
        var request = new org.springframework.mock.web.MockHttpServletRequest(
                "GET", "/oauth2/authorize");
        request.setQueryString("client_id=saved-request-oidc-client&response_type=code");
        var response = new org.springframework.mock.web.MockHttpServletResponse();

        loginRequestCache.saveRequest(request, response);

        assertThat(loginRequestCache.getRequest(request, response))
                .isNotNull()
                .extracting(org.springframework.security.web.savedrequest.SavedRequest::getRedirectUrl)
                .asString()
                .contains("/oauth2/authorize")
                .contains("client_id=saved-request-oidc-client");
    }

    @Test
    void formLoginRejectsIpAfterConfiguredFailureLimit() throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            var loginRequest = post("/login")
                    .with(csrf())
                    .with(request -> {
                        request.setRemoteAddr("198.51.101.10");
                        return request;
                    })
                    .param("username", "UnknownRateLimitedUser")
                    .param("password", "wrong password value");
            if (attempt == 3) {
                var issued = altchaChallengeService.issue(
                        AltchaChallengeService.Purpose.LOGIN_RISK, "v4/24:c6336500");
                loginRequest.param("captchaPayload", solve(issued));
            }
            var action = mockMvc.perform(loginRequest);
            if (attempt <= 3) {
                action.andExpect(status().is3xxRedirection())
                        .andExpect(redirectedUrl("/login?error"));
            } else {
                action.andExpect(status().isTooManyRequests())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .header().exists("Retry-After"));
            }
        }
    }

    @Test
    void formLoginRequiresLoginRiskCaptchaAfterIpFailureThreshold() throws Exception {
        registrationService.register(
                "RiskCaptchaUser", "Risk Captcha User", "risk captcha passphrase");

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/login")
                            .with(csrf())
                            .with(request -> {
                                request.setRemoteAddr("203.0.114.10");
                                return request;
                            })
                            .param("username", "RiskCaptchaUser")
                            .param("password", "wrong password value"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?error"));
        }

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("203.0.114.10");
                            return request;
                        })
                        .param("username", "RiskCaptchaUser")
                        .param("password", "risk captcha passphrase"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?captcha"));
    }

    @Test
    void loginRiskCaptchaAllowsOneAuthenticationAttemptAndCannotBeReused() throws Exception {
        registrationService.register(
                "SolvedRiskUser", "Solved Risk User", "solved risk passphrase");
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/login")
                            .with(csrf())
                            .with(request -> {
                                request.setRemoteAddr("203.0.115.20");
                                return request;
                            })
                            .param("username", "SolvedRiskUser")
                            .param("password", "wrong password value"))
                    .andExpect(status().is3xxRedirection());
        }

        var issued = altchaChallengeService.issue(
                AltchaChallengeService.Purpose.LOGIN_RISK, "v4/24:cb007300");
        String payload = solve(issued);
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("203.0.115.20");
                            return request;
                        })
                        .param("username", "SolvedRiskUser")
                        .param("password", "solved risk passphrase")
                        .param("captchaPayload", payload))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("203.0.115.20");
                            return request;
                        })
                        .param("username", "SolvedRiskUser")
                        .param("password", "solved risk passphrase")
                        .param("captchaPayload", payload))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?captcha"));
    }

    @Test
    void loginRiskPageRendersAltchaSubmissionControls() throws Exception {
        mockMvc.perform(get("/login").queryParam("captcha", ""))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("altcha-widget")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("login-risk.js")));
    }

    @Test
    void temporaryPasswordLoginAlwaysRedirectsToRequiredChangePage() throws Exception {
        var account = registrationService.register(
                "TemporaryLoginUser", "Temporary Login User", "temporary login passphrase");
        jdbcClient.sql("""
                UPDATE password_credentials
                   SET state = 'PASSWORD_CHANGE_REQUIRED',
                       temporary_expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours'
                 WHERE user_id = :userId
                """).param("userId", account.userId()).update();

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "TemporaryLoginUser")
                        .param("password", "temporary login passphrase"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/password-change-required"));
    }

    @Test
    void postgresRateLimitCounterIsAtomicUnderConcurrency() throws Exception {
        int requests = 20;
        var ready = new CountDownLatch(requests);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<PostgresRateLimiter.Decision>>();
            for (int index = 0; index < requests; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return rateLimiter.consume(
                            "REGISTER_IP", "192.0.2.0/24", 5, Duration.ofMinutes(15));
                }));
            }
            ready.await();
            start.countDown();
            var decisions = new java.util.ArrayList<PostgresRateLimiter.Decision>();
            for (var future : futures) {
                decisions.add(future.get());
            }
            assertThat(decisions).filteredOn(PostgresRateLimiter.Decision::allowed).hasSize(5);
            assertThat(decisions).extracting(PostgresRateLimiter.Decision::count)
                    .containsExactlyInAnyOrderElementsOf(
                            java.util.stream.IntStream.rangeClosed(1, requests).boxed().toList());
        }
    }

    @Test
    void captchaChallengeCanBeConsumedExactlyOnceAndOnlyBeforeExpiry() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID id = UUID.randomUUID();
        byte[] digest = transientKeyHasher.hash("captcha", "challenge-one");
        challengeStore.create(
                id,
                "REGISTER",
                digest,
                transientKeyHasher.hash("ip", "192.0.2.0/24"),
                now,
                now.plusMinutes(5));

        byte[] ipHash = transientKeyHasher.hash("ip", "192.0.2.0/24");
        assertThat(challengeStore.consume(
                id, "REGISTER", digest, ipHash, now.plusSeconds(1))).isTrue();
        assertThat(challengeStore.consume(
                id, "REGISTER", digest, ipHash, now.plusSeconds(2))).isFalse();

        UUID expiredId = UUID.randomUUID();
        byte[] expiredDigest = transientKeyHasher.hash("captcha", "challenge-expired");
        challengeStore.create(
                expiredId, "REGISTER", expiredDigest, null, now.minusMinutes(6), now.minusMinutes(1));
        assertThat(challengeStore.consume(
                expiredId, "REGISTER", expiredDigest, null, now)).isFalse();
    }

    @Test
    void cleanupDeletesExpiredTransientRowsButKeepsLiveChallenges() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID expiredId = UUID.randomUUID();
        UUID liveId = UUID.randomUUID();
        challengeStore.create(
                expiredId,
                "REGISTER",
                transientKeyHasher.hash("captcha", "cleanup-expired"),
                null,
                now.minusMinutes(10),
                now.minusMinutes(5));
        challengeStore.create(
                liveId,
                "REGISTER",
                transientKeyHasher.hash("captcha", "cleanup-live"),
                null,
                now,
                now.plusMinutes(5));

        assertThat(transientStateCleanup.cleanupExpired(100)).isGreaterThanOrEqualTo(1);
        Long expiredRows = jdbcClient.sql("SELECT COUNT(*) FROM captcha_challenges WHERE id = :id")
                .param("id", expiredId).query(Long.class).single();
        Long liveRows = jdbcClient.sql("SELECT COUNT(*) FROM captcha_challenges WHERE id = :id")
                .param("id", liveId).query(Long.class).single();
        assertThat(expiredRows).isZero();
        assertThat(liveRows).isOne();
    }

    @Test
    void publicRegistrationConsumesAltchaAndIdempotentReplayDoesNotRepeatRecoveryCodes()
            throws Exception {
        String networkKey = "v4/24:c6336400";
        var issued = altchaChallengeService.issue(
                AltchaChallengeService.Purpose.REGISTER, networkKey);
        String payload = solve(issued);
        UUID idempotencyKey = UUID.randomUUID();
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "username", "PublicRegistrationUser",
                "displayName", "Public Registration User",
                "password", "public registration passphrase",
                "captcha", Map.of(
                        "challengeId", issued.challengeId(),
                        "payload", payload)));

        mockMvc.perform(post("/api/v1/registrations")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        })
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.recoveryCodes.length()").value(10))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.secretsAvailable").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists("X-Request-Id"));

        mockMvc.perform(post("/api/v1/registrations")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        })
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.recoveryCodes.length()").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.secretsAvailable").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.idempotentReplay").value(true));

        String changedBody = objectMapper.writeValueAsString(Map.of(
                "username", "PublicRegistrationUser",
                "displayName", "Changed Display Name",
                "password", "public registration passphrase",
                "captcha", Map.of(
                        "challengeId", issued.challengeId(),
                        "payload", payload)));
        mockMvc.perform(post("/api/v1/registrations")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        })
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(changedBody))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void recoveryCodeCreatesRestrictedSessionAndPasswordResetPreservesUnusedCodes()
            throws Exception {
        var account = registrationService.register(
                "PasswordRecoveryUser", "Password Recovery User", "old recovery passphrase");
        var oldPrincipal = (LasUserPrincipal) userDetailsService.loadUserByUsername(
                "PasswordRecoveryUser");
        String recoveryCode = account.recoveryCodes().getFirst();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String securityEpoch = jdbcClient.sql(
                        "SELECT security_epoch FROM system_security_state WHERE id = 1")
                .query(String.class).single();
        jdbcClient.sql("""
                INSERT INTO user_sessions
                    (id, sid, user_id, status, remembered, admin_session,
                     credentials_version, security_epoch, created_at, last_seen_at,
                     idle_expires_at, absolute_expires_at, last_password_auth_at)
                VALUES (:id, :sid, :userId, 'ACTIVE', false, false, 1, :securityEpoch,
                        :now, :now, :idleExpiresAt, :absoluteExpiresAt, :now)
                """).param("id", UUID.randomUUID()).param("sid", UUID.randomUUID().toString())
                .param("userId", account.userId()).param("securityEpoch", securityEpoch)
                .param("now", now).param("idleExpiresAt", now.plusHours(1))
                .param("absoluteExpiresAt", now.plusDays(1)).update();

        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("recovery-revocation-client-" + UUID.randomUUID())
                .clientSecret("{noop}secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/recovery-callback")
                .scope(OidcScopes.OPENID)
                .build();
        registeredClients.save(client);
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(account.userId().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(java.util.Set.of(OidcScopes.OPENID))
                .build();
        authorizations.save(authorization);

        var startResult = mockMvc.perform(post("/api/v1/recovery/sessions")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.80");
                            return request;
                        })
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "PasswordRecoveryUser",
                                "credential", recoveryCode,
                                "credentialType", "RECOVERY_CODE"))))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        String setCookie = startResult.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("__Host-las_recovery=")
                .contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
        String token = setCookie.substring(
                "__Host-las_recovery=".length(), setCookie.indexOf(';'));
        Cookie recoveryCookie = new Cookie("__Host-las_recovery", token);
        recoveryCookie.setSecure(true);
        recoveryCookie.setPath("/");

        mockMvc.perform(get("/account/security"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(put("/api/v1/recovery/password")
                        .with(csrf())
                        .cookie(recoveryCookie)
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"newPassword\":\"new recovery passphrase\"}"))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        var updated = (LasUserPrincipal) userDetailsService.loadUserByUsername("PasswordRecoveryUser");
        assertThat(passwordEncoder.matches("new recovery passphrase", updated.getPassword())).isTrue();
        assertThat(updated.credentialsVersion()).isEqualTo(oldPrincipal.credentialsVersion() + 1);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_codes
                 WHERE user_id = :userId AND used_at IS NULL AND revoked_at IS NULL
                """).param("userId", account.userId()).query(Long.class).single()).isEqualTo(9);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_codes
                 WHERE user_id = :userId AND used_at IS NOT NULL AND revoked_at IS NULL
                """).param("userId", account.userId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM user_sessions
                 WHERE user_id = :userId AND status = 'ACTIVE'
                """).param("userId", account.userId()).query(Long.class).single()).isZero();
        assertThat(authorizations.findById(authorization.getId())).isNull();
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_id = :userId AND event_type = 'GLOBAL_LOGOUT_REQUESTED'
                """).param("userId", account.userId().toString())
                .query(Long.class).single()).isEqualTo(1);

        mockMvc.perform(get("/account/security").with(user(oldPrincipal)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?sessionExpired"));
        mockMvc.perform(put("/api/v1/recovery/password")
                        .with(csrf())
                        .cookie(recoveryCookie)
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"newPassword\":\"another recovery passphrase\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void recoveryCredentialIsCaseSensitiveSingleUseAndDoesNotRevealAccountExistence()
            throws Exception {
        var account = registrationService.register(
                "CaseSensitiveRecovery", "Case Sensitive Recovery", "case recovery passphrase");
        String recoveryCode = account.recoveryCodes().getFirst();

        mockMvc.perform(post("/api/v1/recovery/sessions")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.81");
                            return request;
                        })
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "casesensitiverecovery",
                                "credential", recoveryCode,
                                "credentialType", "RECOVERY_CODE"))))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("RECOVERY_CREDENTIAL_INVALID"));

        mockMvc.perform(post("/api/v1/recovery/sessions")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.82");
                            return request;
                        })
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "UnknownRecoveryUser",
                                "credential", recoveryCode,
                                "credentialType", "RECOVERY_CODE"))))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("RECOVERY_CREDENTIAL_INVALID"));

        mockMvc.perform(post("/api/v1/recovery/sessions")
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "CaseSensitiveRecovery",
                                "credential", recoveryCode,
                                "credentialType", "RECOVERY_CODE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void passwordRecoveryPageIsPublicAndContainsBothRestrictedSteps() throws Exception {
        mockMvc.perform(get("/password-recovery"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("recovery-credential-form")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("recovery-password-form")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("password-recovery.js")));
        mockMvc.perform(get("/assets/password-recovery.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/assets/passport.css"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("::-ms-reveal")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString(
                                "#recovery-footer[hidden]")));
    }

    @Test
    void recoveryCodeCanCreateOnlyOneSessionUnderConcurrency() throws Exception {
        var account = registrationService.register(
                "ConcurrentRecovery", "Concurrent Recovery", "concurrent recovery passphrase");
        String code = account.recoveryCodes().getFirst();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            passwordRecoveryService.start(
                                    "ConcurrentRecovery",
                                    code,
                                    PasswordRecoveryService.CredentialType.RECOVERY_CODE,
                                    null,
                                    "v4/24:c00002" + index);
                            return true;
                        } catch (RecoveryFlowException exception) {
                            assertThat(exception.code()).isEqualTo("RECOVERY_CREDENTIAL_INVALID");
                            return false;
                        }
                    }))
                    .toList();
            ready.await();
            start.countDown();
            int successes = 0;
            for (var future : futures) {
                if (future.get()) successes++;
            }
            assertThat(successes).isEqualTo(1);
        }

        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_sessions WHERE user_id = :userId
                """).param("userId", account.userId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM recovery_codes
                 WHERE user_id = :userId AND used_at IS NOT NULL
                """).param("userId", account.userId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void recoveryRequiresCaptchaOnlyAfterRiskThreshold() throws Exception {
        var account = registrationService.register(
                "RiskRecoveryUser", "Risk Recovery User", "risk recovery passphrase");
        String networkKey = "v4/24:c0000200";
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/v1/recovery/sessions")
                            .with(csrf())
                            .header("Origin", "http://127.0.0.1:9000")
                            .with(request -> {
                                request.setRemoteAddr("192.0.2.128");
                                return request;
                            })
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "RiskRecoveryUser",
                                    "credential", "LAS-INVALID-CODE",
                                    "credentialType", "RECOVERY_CODE"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.code").value("RECOVERY_CREDENTIAL_INVALID"));
        }

        mockMvc.perform(post("/api/v1/recovery/sessions")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.128");
                            return request;
                        })
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "RiskRecoveryUser",
                                "credential", account.recoveryCodes().getFirst(),
                                "credentialType", "RECOVERY_CODE"))))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("CAPTCHA_REQUIRED"));

        var issued = altchaChallengeService.issue(
                AltchaChallengeService.Purpose.RECOVERY_RISK, networkKey);
        mockMvc.perform(post("/api/v1/recovery/sessions")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.128");
                            return request;
                        })
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "RiskRecoveryUser",
                                "credential", account.recoveryCodes().getFirst(),
                                "credentialType", "RECOVERY_CODE",
                                "captcha", Map.of(
                                        "challengeId", issued.challengeId(),
                                        "payload", solve(issued))))))
                .andExpect(status().isNoContent());
    }

    @Test
    void registrationRejectsReusedCaptchaAndUnknownJsonFields() throws Exception {
        var issued = altchaChallengeService.issue(
                AltchaChallengeService.Purpose.REGISTER, "v4/24:cb007100");
        String payload = solve(issued);
        String firstBody = registrationBody(
                "CaptchaFirstUser", "Captcha First User", issued.challengeId(), payload);
        mockMvc.perform(post("/api/v1/registrations")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        })
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(firstBody))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/registrations")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        })
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(registrationBody(
                                "CaptchaReplayUser", "Captcha Replay User",
                                issued.challengeId(), payload)))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("CAPTCHA_INVALID"));

        mockMvc.perform(post("/api/v1/captcha/challenges")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("""
                                {"purpose":"REGISTER","unexpected":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void captchaChallengeEndpointIsPublicButStillRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/captcha/challenges")
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"purpose\":\"REGISTER\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/captcha/challenges")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"purpose\":\"REGISTER\"}"))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.challengeId").isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.parameters.algorithm").value("PBKDF2/SHA-256"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.signature").isNotEmpty());

        mockMvc.perform(post("/api/v1/captcha/challenges")
                        .with(csrf())
                        .header("Origin", "https://attacker.example")
                        .contentType("application/json")
                        .content("{\"purpose\":\"REGISTER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void passportLoginAndRegistrationUiAndSelfHostedAssetsArePublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("LazyAlienServer")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("registration-form")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("altcha-widget")));
        mockMvc.perform(get("/assets/passport.css"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/webjars/altcha/3.1.0/dist/main/altcha.min.js"))
                .andExpect(status().isOk());
    }

    @Test
    void htmlErrorsUsePassportErrorPageWithoutExposingDetails() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/missing-page")
                        .accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .view().name("error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("没有找到这个页面")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("返回通行证首页")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("Whitelabel Error Page"))));

        mockMvc.perform(get("/error")
                        .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/failed-page")
                        .accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(status().isInternalServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .view().name("error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString(
                                "服务暂时无法处理请求")));
    }

    @Test
    void registrationBindsCaptchaToIpPrefixAndFailedAttemptsStillReachRateLimit()
            throws Exception {
        var issued = altchaChallengeService.issue(
                AltchaChallengeService.Purpose.REGISTER, "v4/24:c0000200");
        String payload = solve(issued);
        mockMvc.perform(post("/api/v1/registrations")
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .with(request -> {
                            request.setRemoteAddr("192.0.3.10");
                            return request;
                        })
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(registrationBody(
                                "WrongNetworkUser", "Wrong Network User",
                                issued.challengeId(), payload)))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("CAPTCHA_INVALID"));

        for (int attempt = 1; attempt <= 6; attempt++) {
            var action = mockMvc.perform(post("/api/v1/registrations")
                    .with(csrf())
                    .header("Origin", "http://127.0.0.1:9000")
                    .with(request -> {
                        request.setRemoteAddr("192.0.4.10");
                        return request;
                    })
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(registrationBody(
                            "RateLimitedUser", "Rate Limited User",
                            UUID.randomUUID(), "invalid-payload")));
            if (attempt <= 5) {
                action.andExpect(status().isBadRequest())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .jsonPath("$.code").value("CAPTCHA_INVALID"));
            } else {
                action.andExpect(status().isTooManyRequests())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .header().exists("Retry-After"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .jsonPath("$.code").value("RATE_LIMITED"));
            }
        }
    }

    @Test
    void refreshTokenRotatesAndReuseRevokesTheWholeFamily() throws Exception {
        RefreshFixture fixture = createRefreshFixture("RefreshRotationUser");

        MvcResult refreshed = refresh(fixture, fixture.refreshToken());

        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        String rotatedToken = objectMapper.readTree(refreshed.getResponse().getContentAsString())
                .get("refresh_token").asText();
        assertThat(rotatedToken).isNotEqualTo(fixture.refreshToken());
        assertThat(refreshHistoryCount(fixture.authorizationId(), "CURRENT")).isEqualTo(1);
        assertThat(refreshHistoryCount(fixture.authorizationId(), "ROTATED")).isEqualTo(1);

        MvcResult reused = refresh(fixture, fixture.refreshToken());

        assertThat(reused.getResponse().getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(reused.getResponse().getContentAsString())
                .get("error").asText()).isEqualTo("invalid_grant");
        assertThat(authorizations.findById(fixture.authorizationId())).isNull();
        assertThat(refreshHistoryCount(fixture.authorizationId(), "REVOKED")).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId
                   AND event_type = 'REFRESH_TOKEN_REUSE_DETECTED'
                   AND severity = 'CRITICAL'
                """).param("userId", fixture.userId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_id = :userId
                   AND event_type = 'BACKCHANNEL_LOGOUT_REQUESTED'
                   AND payload_json ->> 'reason' = 'REFRESH_TOKEN_REUSE_DETECTED'
                """).param("userId", fixture.userId().toString())
                .query(Long.class).single()).isEqualTo(1);

        MvcResult rotatedAfterReuse = refresh(fixture, rotatedToken);
        assertThat(rotatedAfterReuse.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void rpInitiatedLogoutRevokesGlobalStateAndReturnsRegisteredState() throws Exception {
        var account = registrationService.register(
                "RpLogoutUser", "RP Logout User", "rp logout unique passphrase");
        String clientId = "rp-logout-" + UUID.randomUUID();
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode("rp-logout-secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/callback")
                .postLogoutRedirectUri("https://client.example/logged-out")
                .scope(OidcScopes.OPENID)
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .build();
        registeredClients.save(client);
        jdbcClient.sql("""
                INSERT INTO registered_client_metadata
                    (registered_client_id, client_type, status, config_version,
                     resource_audiences, require_pkce, updated_at)
                VALUES (:clientId, 'FIRST_PARTY', 'ACTIVE', 1,
                        ARRAY['urn:las:test']::text[], true, CURRENT_TIMESTAMP)
                """)
                .param("clientId", client.getId())
                .update();

        var session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .session(session)
                        .with(csrf())
                        .param("username", "RpLogoutUser")
                        .param("password", "rp logout unique passphrase"))
                .andExpect(status().is3xxRedirection());
        var securityContext = (org.springframework.security.core.context.SecurityContext) session.getAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY);
        Authentication authentication = securityContext.getAuthentication();
        String sessionSid = (String) session.getAttribute(
                org.las.sso.session.UserSessionService.SID_SESSION_ATTRIBUTE);
        Instant issuedAt = Instant.now().minusSeconds(10);
        var idToken = new OidcIdToken(
                "id-token-" + UUID.randomUUID(),
                issuedAt,
                issuedAt.plus(Duration.ofMinutes(10)),
                Map.of(
                        "iss", "http://127.0.0.1:9000",
                        "sub", account.userId().toString(),
                        "aud", List.of(clientId),
                        "sid", sessionSid,
                        "iat", issuedAt,
                        "exp", issuedAt.plus(Duration.ofMinutes(10))));
        String authorizationId = UUID.randomUUID().toString();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(authorizationId)
                .principalName(account.userId().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(java.util.Set.of(OidcScopes.OPENID))
                .attribute(Principal.class.getName(), authentication)
                .token(idToken, metadata -> metadata.put(
                        OAuth2Authorization.Token.CLAIMS_METADATA_NAME, idToken.getClaims()))
                .build();
        authorizations.save(authorization);
        OAuth2Authorization storedLogoutAuthorization = authorizations.findByToken(
                idToken.getTokenValue(), new org.springframework.security.oauth2.server.authorization.OAuth2TokenType(
                        "id_token"));
        assertThat(storedLogoutAuthorization).isNotNull();
        assertThat(storedLogoutAuthorization.getToken(OidcIdToken.class).isActive()).isTrue();
        assertThat(storedLogoutAuthorization.getToken(OidcIdToken.class).getToken().getAudience())
                .contains(clientId);
        assertThat((String) storedLogoutAuthorization.getToken(OidcIdToken.class)
                .getToken().getClaim("sid"))
                .isEqualTo(sessionSid);
        assertThat(((Authentication) storedLogoutAuthorization.getAttribute(Principal.class.getName()))
                .getName()).isEqualTo(authentication.getName());
        assertThat(registeredClients.findById(client.getId()).getPostLogoutRedirectUris())
                .contains("https://client.example/logged-out");
        mockMvc.perform(get("/connect/logout")
                        .queryParam("id_token_hint", idToken.getTokenValue())
                        .queryParam("post_logout_redirect_uri", "https://client.example/logged-out")
                        .queryParam("state", "server-side-random-state"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "https://client.example/logged-out?state=server-side-random-state"));

        assertThat(authorizations.findById(authorizationId)).isNull();
        assertThat(jdbcClient.sql("""
                SELECT status::text FROM user_sessions
                 WHERE user_id = :userId ORDER BY created_at DESC LIMIT 1
                """).param("userId", account.userId()).query(String.class).single())
                .isEqualTo("REVOKED");
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_id = :userId
                   AND event_type = 'GLOBAL_LOGOUT_REQUESTED'
                   AND payload_json ->> 'reason' = 'SSO_LOGOUT'
                """).param("userId", account.userId().toString())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void refreshTokenFamilyHasAnAbsoluteFourteenDayDeadline() throws Exception {
        RefreshFixture fixture = createRefreshFixture("RefreshDeadlineUser");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                UPDATE oauth2_authorization_security
                   SET family_created_at = :createdAt,
                       family_absolute_expires_at = :expiresAt
                 WHERE authorization_id = :authorizationId
                """)
                .param("createdAt", now.minusDays(15))
                .param("expiresAt", now.minusDays(1))
                .param("authorizationId", fixture.authorizationId())
                .update();

        MvcResult expired = refresh(fixture, fixture.refreshToken());

        assertThat(expired.getResponse().getStatus()).isEqualTo(400);
        assertThat(authorizations.findById(fixture.authorizationId())).isNull();
        assertThat(refreshHistoryCount(fixture.authorizationId(), "REVOKED")).isEqualTo(1);
    }

    @Test
    void refreshTokenRejectsAChangedCredentialVersion() throws Exception {
        RefreshFixture fixture = createRefreshFixture("RefreshCredentialUser");
        jdbcClient.sql("""
                UPDATE password_credentials
                   SET credentials_version = credentials_version + 1
                 WHERE user_id = :userId
                """)
                .param("userId", fixture.userId())
                .update();

        MvcResult invalidated = refresh(fixture, fixture.refreshToken());

        assertThat(invalidated.getResponse().getStatus()).isEqualTo(400);
        assertThat(authorizations.findById(fixture.authorizationId())).isNull();
        assertThat(refreshHistoryCount(fixture.authorizationId(), "REVOKED")).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId
                   AND event_type = 'REFRESH_TOKEN_SECURITY_STATE_INVALID'
                   AND severity = 'WARNING'
                """).param("userId", fixture.userId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_id = :userId
                   AND payload_json ->> 'reason' = 'REFRESH_TOKEN_SECURITY_STATE_INVALID'
                """).param("userId", fixture.userId().toString())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void refreshTokenRejectsSecurityClientAndAccountVersionChanges() throws Exception {
        RefreshFixture securityFixture = createRefreshFixture("RefreshEpochUser");
        jdbcClient.sql("""
                UPDATE system_security_state
                   SET security_epoch = :epoch, updated_at = CURRENT_TIMESTAMP,
                       change_reason = 'REFRESH_TEST'
                 WHERE id = 1
                """)
                .param("epoch", UUID.randomUUID().toString())
                .update();
        assertThat(refresh(securityFixture, securityFixture.refreshToken())
                .getResponse().getStatus()).isEqualTo(400);

        RefreshFixture clientFixture = createRefreshFixture("RefreshClientVersionUser");
        jdbcClient.sql("""
                UPDATE registered_client_metadata
                   SET config_version = config_version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE registered_client_id = (
                     SELECT registered_client_id FROM oauth2_authorization WHERE id = :authorizationId
                 )
                """)
                .param("authorizationId", clientFixture.authorizationId())
                .update();
        assertThat(refresh(clientFixture, clientFixture.refreshToken())
                .getResponse().getStatus()).isEqualTo(400);

        RefreshFixture accountFixture = createRefreshFixture("RefreshFrozenUser");
        jdbcClient.sql("UPDATE users SET account_status = 'FROZEN' WHERE id = :userId")
                .param("userId", accountFixture.userId())
                .update();
        assertThat(refresh(accountFixture, accountFixture.refreshToken())
                .getResponse().getStatus()).isEqualTo(400);

        assertThat(authorizations.findById(securityFixture.authorizationId())).isNull();
        assertThat(authorizations.findById(clientFixture.authorizationId())).isNull();
        assertThat(authorizations.findById(accountFixture.authorizationId())).isNull();
    }

    @Test
    void refreshTokenImportsADeployTimeLegacyCurrentToken() throws Exception {
        RefreshFixture fixture = createRefreshFixture("RefreshLegacyUser");
        jdbcClient.sql("DELETE FROM oauth2_refresh_token_history WHERE authorization_id = :authorizationId")
                .param("authorizationId", fixture.authorizationId())
                .update();

        MvcResult refreshed = refresh(fixture, fixture.refreshToken());

        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        assertThat(refreshHistoryCount(fixture.authorizationId(), "ROTATED")).isEqualTo(1);
        assertThat(refreshHistoryCount(fixture.authorizationId(), "CURRENT")).isEqualTo(1);
    }

    @Test
    void concurrentRefreshSerializesAndRevokesTheReusedFamily() throws Exception {
        RefreshFixture fixture = createRefreshFixture("RefreshConcurrentUser");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return refresh(fixture, fixture.refreshToken()).getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return refresh(fixture, fixture.refreshToken()).getResponse().getStatus();
            });
            ready.await();
            start.countDown();

            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(200, 400);
        } finally {
            executor.shutdownNow();
        }
        assertThat(authorizations.findById(fixture.authorizationId())).isNull();
        assertThat(refreshHistoryCount(fixture.authorizationId(), "REVOKED")).isEqualTo(2);
    }

    private RefreshFixture createRefreshFixture(String username) {
        var account = registrationService.register(
                username, username, "refresh fixture unique passphrase");
        String clientId = "client-" + UUID.randomUUID();
        String clientSecret = "refresh-fixture-client-secret";
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://client.example/callback")
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(14))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
        registeredClients.save(client);
        jdbcClient.sql("""
                INSERT INTO registered_client_metadata
                    (registered_client_id, client_type, status, config_version,
                     resource_audiences, require_pkce, updated_at)
                VALUES (:clientId, 'FIRST_PARTY', 'ACTIVE', 1,
                        ARRAY['urn:las:test']::text[], true, CURRENT_TIMESTAMP)
                """)
                .param("clientId", client.getId())
                .update();
        jdbcClient.sql("""
                INSERT INTO registered_client_backchannel_logout_uris
                    (registered_client_id, uri, session_required)
                VALUES (:clientId, 'https://client.example/backchannel-logout', true)
                """)
                .param("clientId", client.getId())
                .update();

        var principal = (LasUserPrincipal) userDetailsService.loadUserByUsername(username);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        Instant issuedAt = Instant.now();
        String rawRefreshToken = "refresh-" + UUID.randomUUID();
        String authorizationId = UUID.randomUUID().toString();
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(authorizationId)
                .principalName(account.userId().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(java.util.Set.of(OidcScopes.PROFILE))
                .attribute(Principal.class.getName(), authentication)
                .refreshToken(new OAuth2RefreshToken(
                        rawRefreshToken, issuedAt, issuedAt.plus(Duration.ofDays(14))))
                .build();
        authorizations.save(authorization);
        return new RefreshFixture(
                account.userId(), authorizationId, clientId, clientSecret, rawRefreshToken);
    }

    private MvcResult refresh(RefreshFixture fixture, String refreshToken) throws Exception {
        return mockMvc.perform(post("/oauth2/token")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.httpBasic(
                                        fixture.clientId(), fixture.clientSecret()))
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken))
                .andReturn();
    }

    @Test
    void administratorUserManagementRequiresRoleAndRecentAuthenticationAndAuditsStateChanges()
            throws Exception {
        var actorAccount = registrationService.register(
                "AdminManagerActor", "Admin Manager Actor", "admin manager actor passphrase");
        var targetAccount = registrationService.register(
                "AdminManagedUser", "Admin Managed User", "admin managed user passphrase");
        var actorUser = (LasUserPrincipal) userDetailsService.loadUserByUsername("AdminManagerActor");
        var adminPrincipal = new LasUserPrincipal(
                actorUser.userId(), actorUser.loginUsername(), actorUser.getPassword(),
                actorUser.credentialsVersion(), true, false,
                List.of(new SimpleGrantedAuthority("ROLE_SSO_ADMIN")));
        var ordinaryPrincipal = (LasUserPrincipal) userDetailsService.loadUserByUsername("AdminManagedUser");

        mockMvc.perform(get("/api/v1/admin/users").with(user(ordinaryPrincipal)))
                .andExpect(status().isForbidden());

        var adminSession = new MockHttpSession();
        mockMvc.perform(get("/admin/users")
                        .session(adminSession)
                        .with(user(adminPrincipal)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/users/{userId}", targetAccount.userId())
                        .session(adminSession)
                        .with(user(adminPrincipal)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/users/{userId}/freeze", targetAccount.userId())
                        .session(adminSession)
                        .with(user(adminPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"missing recent authentication\"}"))
                .andExpect(status().isForbidden());

        adminSession.setAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE, Instant.now());
        var targetSession = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .session(targetSession)
                        .with(csrf())
                        .param("username", "AdminManagedUser")
                        .param("password", "admin managed user passphrase"))
                .andExpect(status().is3xxRedirection());
        String targetSid = (String) targetSession.getAttribute(
                org.las.sso.session.UserSessionService.SID_SESSION_ATTRIBUTE);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/freeze", targetAccount.userId())
                        .session(adminSession)
                        .with(user(adminPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"confirmed security incident\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbcClient.sql("SELECT account_status::text FROM users WHERE id = :userId")
                .param("userId", targetAccount.userId()).query(String.class).single())
                .isEqualTo("FROZEN");
        assertThat(jdbcClient.sql("SELECT status::text FROM user_sessions WHERE sid = :sid")
                .param("sid", targetSid).query(String.class).single()).isEqualTo("REVOKED");

        mockMvc.perform(post("/api/v1/admin/users/{userId}/unfreeze", targetAccount.userId())
                        .session(adminSession)
                        .with(user(adminPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"incident review completed\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/admin/users/{userId}/revoke-sessions", targetAccount.userId())
                        .session(adminSession)
                        .with(user(adminPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"routine access reset\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbcClient.sql("SELECT account_status::text FROM users WHERE id = :userId")
                .param("userId", targetAccount.userId()).query(String.class).single())
                .isEqualTo("ACTIVE");
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM admin_audit_events
                 WHERE actor_user_id = :actorUserId AND target_id = :targetId
                   AND action IN ('ADMIN_USER_FREEZE', 'ADMIN_USER_UNFREEZE',
                                  'ADMIN_USER_REVOKE_SESSIONS')
                """).param("actorUserId", actorAccount.userId())
                .param("targetId", targetAccount.userId().toString())
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM security_events
                 WHERE user_id = :userId
                   AND event_type IN ('ACCOUNT_FROZEN_BY_ADMIN',
                                      'ACCOUNT_UNFROZEN_BY_ADMIN',
                                      'SESSIONS_REVOKED_BY_ADMIN')
                """).param("userId", targetAccount.userId())
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_id = :userId AND event_type = 'GLOBAL_LOGOUT_REQUESTED'
                   AND payload_json ->> 'reason' IN ('ADMIN_FROZEN', 'ADMIN_REVOKED_SESSIONS')
                """).param("userId", targetAccount.userId().toString())
                .query(Long.class).single()).isEqualTo(2);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/freeze", actorAccount.userId())
                        .session(adminSession)
                        .with(user(adminPrincipal))
                        .with(csrf())
                        .header("Origin", "http://127.0.0.1:9000")
                        .contentType("application/json")
                        .content("{\"reason\":\"self action must fail\"}"))
                .andExpect(status().isForbidden());
    }

    private long refreshHistoryCount(String authorizationId, String status) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM oauth2_refresh_token_history
                 WHERE authorization_id = :authorizationId AND status = CAST(:status AS refresh_token_status)
                """)
                .param("authorizationId", authorizationId)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private record RefreshFixture(
            UUID userId,
            String authorizationId,
            String clientId,
            String clientSecret,
            String refreshToken) {
    }

    private String registrationBody(
            String username, String displayName, UUID challengeId, String payload) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", username,
                "displayName", displayName,
                "password", "unique registration passphrase",
                "captcha", Map.of("challengeId", challengeId, "payload", payload)));
    }

    private String solve(AltchaChallengeService.IssuedChallenge issued) throws Exception {
        var challenge = new Altcha.Challenge(issued.parameters(), issued.signature());
        var solution = Altcha.solveChallenge(challenge, Altcha.kdf("PBKDF2/SHA-256"));
        var payload = new Altcha.Payload(challenge, solution);
        return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(payload));
    }
}
