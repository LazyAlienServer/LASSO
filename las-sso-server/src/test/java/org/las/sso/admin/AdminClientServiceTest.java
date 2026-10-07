package org.las.sso.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.identity.UuidV7Generator;
import org.las.sso.logout.BackChannelLogoutOutbox;
import org.las.sso.maintenance.AdminBootstrapService;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.session.UserSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        properties =
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AdminClientServiceTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService registrationService;

    @Autowired AdminBootstrapService adminBootstrapService;

    @Autowired AdminClientService clients;

    @Autowired RegisteredClientRepository registeredClients;

    @Autowired TokenSettings tokenSettings;

    @Autowired BackChannelLogoutOutbox backChannelLogout;

    @Autowired UuidV7Generator uuidGenerator;

    @Autowired PlatformTransactionManager transactionManager;

    @Autowired JdbcClient jdbcClient;

    @Autowired PasswordEncoder passwordEncoder;

    @Autowired OAuth2AuthorizationService authorizationService;

    @Autowired UserSessionService userSessions;

    @Autowired MockMvc mockMvc;

    @Test
    void createStoresClientMetadataAndReturnsSecretOnce() {
        UUID adminId = administrator("ClientAdminCreate");
        AdminClientService.CreateResult result =
                clients.create(
                        new AdminClientService.CreateRequest(
                                "partner-demo",
                                "Partner Demo",
                                "REVIEWED_PARTNER",
                                List.of("https://partner.example/callback"),
                                List.of("https://partner.example/logged-out"),
                                List.of("openid", "profile"),
                                List.of("urn:las:resource:partner"),
                                "https://partner.example/internal/oidc/backchannel-logout"),
                        adminId,
                        null,
                        "partner onboarding",
                        UUID.randomUUID());

        assertThat(result.clientId()).isEqualTo("partner-demo");
        assertThat(result.clientSecret()).startsWith("las_client_");
        assertThat(result.configVersion()).isOne();
        RegisteredClient stored = clients.findByClientId("partner-demo");
        assertThat(passwordEncoder.matches(result.clientSecret(), stored.getClientSecret()))
                .isTrue();
        String type =
                jdbcClient
                        .sql(
                                """
                SELECT client_type::text FROM registered_client_metadata
                 WHERE registered_client_id = :id
                """)
                        .param("id", stored.getId())
                        .query(String.class)
                        .single();
        assertThat(type).isEqualTo("REVIEWED_PARTNER");
        Long audit =
                jdbcClient
                        .sql(
                                """
                SELECT COUNT(*) FROM admin_audit_events
                 WHERE action = 'ADMIN_CLIENT_CREATE' AND target_id = 'partner-demo'
                """)
                        .query(Long.class)
                        .single();
        assertThat(audit).isOne();
    }

    @Test
    void createRejectsInvalidUrisScopesAudiencesAndDuplicateIds() {
        UUID adminId = administrator("ClientAdminValidate");
        AdminClientService.CreateRequest base =
                new AdminClientService.CreateRequest(
                        null,
                        "Validate",
                        "FIRST_PARTY",
                        List.of("https://valid.example/callback"),
                        List.of(),
                        List.of("openid", "profile"),
                        List.of("urn:las:resource:validate"),
                        null);

        assertThatThrownBy(
                        () ->
                                clients.create(
                                        withUris(base, List.of("http://evil.example/callback")),
                                        adminId,
                                        null,
                                        "r",
                                        UUID.randomUUID()))
                .extracting("code")
                .isEqualTo("INVALID_REDIRECT_URI");
        assertThatThrownBy(
                        () ->
                                clients.create(
                                        withUris(base, List.of("https://ok.example/cb#frag")),
                                        adminId,
                                        null,
                                        "r",
                                        UUID.randomUUID()))
                .extracting("code")
                .isEqualTo("INVALID_REDIRECT_URI");
        assertThatThrownBy(
                        () ->
                                clients.create(
                                        withUris(base, List.of("https://ok.example/*")),
                                        adminId,
                                        null,
                                        "r",
                                        UUID.randomUUID()))
                .extracting("code")
                .isEqualTo("INVALID_REDIRECT_URI");
        assertThatThrownBy(
                        () ->
                                clients.create(
                                        withScopes(base, List.of("openid", "email")),
                                        adminId,
                                        null,
                                        "r",
                                        UUID.randomUUID()))
                .extracting("code")
                .isEqualTo("INVALID_SCOPE");
        assertThatThrownBy(
                        () ->
                                clients.create(
                                        withAudiences(base, List.of()),
                                        adminId,
                                        null,
                                        "r",
                                        UUID.randomUUID()))
                .extracting("code")
                .isEqualTo("AUDIENCE_REQUIRED");
        assertThatThrownBy(
                        () ->
                                clients.create(
                                        withClientId(base, "ab"),
                                        adminId,
                                        null,
                                        "r",
                                        UUID.randomUUID()))
                .extracting("code")
                .isEqualTo("INVALID_CLIENT_ID");
        assertThatThrownBy(
                        () ->
                                clients.create(
                                        new AdminClientService.CreateRequest(
                                                null,
                                                "Invalid type",
                                                "TYPO_PARTNER",
                                                base.redirectUris(),
                                                base.postLogoutRedirectUris(),
                                                base.scopes(),
                                                base.resourceAudiences(),
                                                base.backchannelLogoutUri()),
                                        adminId,
                                        null,
                                        "r",
                                        UUID.randomUUID()))
                .extracting("code")
                .isEqualTo("INVALID_CLIENT_TYPE");

        AdminClientService.CreateRequest first =
                withClientId(withUris(base, List.of("https://dup.example/callback")), "dup-client");
        clients.create(first, adminId, null, "r", UUID.randomUUID());
        assertThatThrownBy(() -> clients.create(first, adminId, null, "r", UUID.randomUUID()))
                .extracting("code")
                .isEqualTo("CLIENT_ID_CONFLICT");
    }

    @Test
    void patchScopeShrinkRevokesAndBumpsVersion() {
        UUID adminId = administrator("ClientAdminShrink");
        var user =
                registrationService.register(
                        "ClientShrinkUser", "Client Shrink User", "client shrink passphrase");
        clients.create(
                new AdminClientService.CreateRequest(
                        "shrink-client",
                        "Shrink",
                        "FIRST_PARTY",
                        List.of("https://shrink.example/callback"),
                        List.of(),
                        List.of("openid", "profile"),
                        List.of("urn:las:resource:shrink"),
                        null),
                adminId,
                null,
                "r",
                UUID.randomUUID());
        insertAuthorization("shrink-client", user.userId().toString());

        AdminClientService.PatchResult result =
                clients.patch(
                        "shrink-client",
                        new AdminClientService.PatchRequest(
                                "Shrink",
                                "FIRST_PARTY",
                                List.of("https://shrink.example/callback"),
                                List.of(),
                                List.of("openid"),
                                List.of("urn:las:resource:shrink"),
                                null),
                        adminId,
                        null,
                        "scope tightened",
                        UUID.randomUUID());

        assertThat(result.revocationTriggered()).isTrue();
        assertThat(result.revokedAuthorizationCount()).isOne();
        assertThat(result.configVersion()).isEqualTo(2);
        Long remaining =
                jdbcClient
                        .sql(
                                """
                SELECT COUNT(*) FROM oauth2_authorization WHERE registered_client_id = :id
                """)
                        .param("id", registeredClientId("shrink-client"))
                        .query(Long.class)
                        .single();
        assertThat(remaining).isZero();
    }

    @Test
    void patchNameOnlyKeepsVersion() {
        UUID adminId = administrator("ClientAdminName");
        clients.create(
                new AdminClientService.CreateRequest(
                        "name-client",
                        "Old Name",
                        "FIRST_PARTY",
                        List.of("https://name.example/callback"),
                        List.of(),
                        List.of("openid", "profile"),
                        List.of("urn:las:resource:name"),
                        null),
                adminId,
                null,
                "r",
                UUID.randomUUID());

        AdminClientService.PatchResult result =
                clients.patch(
                        "name-client",
                        new AdminClientService.PatchRequest(
                                "New Name",
                                "FIRST_PARTY",
                                List.of("https://name.example/callback"),
                                List.of(),
                                List.of("openid", "profile"),
                                List.of("urn:las:resource:name"),
                                null),
                        adminId,
                        null,
                        "rename",
                        UUID.randomUUID());

        assertThat(result.revocationTriggered()).isFalse();
        assertThat(result.configVersion()).isOne();
    }

    @Test
    void patchScopeExpansionBumpsVersionWithoutRevokingExistingAuthorization() {
        UUID adminId = administrator("ClientAdminExpand");
        clients.create(
                new AdminClientService.CreateRequest(
                        "expand-client",
                        "Expand",
                        "REVIEWED_PARTNER",
                        List.of("https://expand.example/callback"),
                        List.of(),
                        List.of("openid"),
                        List.of("urn:las:resource:expand"),
                        null),
                adminId,
                null,
                "r",
                UUID.randomUUID());

        AdminClientService.PatchResult result =
                clients.patch(
                        "expand-client",
                        new AdminClientService.PatchRequest(
                                "Expand",
                                "REVIEWED_PARTNER",
                                List.of("https://expand.example/callback"),
                                List.of(),
                                List.of("openid", "profile"),
                                List.of("urn:las:resource:expand"),
                                null),
                        adminId,
                        null,
                        "scope expanded",
                        UUID.randomUUID());

        assertThat(result.revocationTriggered()).isFalse();
        assertThat(result.configVersion()).isEqualTo(2);
    }

    @Test
    void disableRevokesAndRejectsNewAuthorizations() {
        UUID adminId = administrator("ClientAdminDisable");
        var user =
                registrationService.register(
                        "ClientDisableUser", "Client Disable User", "client disable passphrase");
        clients.create(
                new AdminClientService.CreateRequest(
                        "disable-client",
                        "Disable",
                        "FIRST_PARTY",
                        List.of("https://disable.example/callback"),
                        List.of(),
                        List.of("openid", "profile"),
                        List.of("urn:las:resource:disable"),
                        "https://disable.example/internal/oidc/backchannel-logout"),
                adminId,
                null,
                "r",
                UUID.randomUUID());
        insertAuthorization("disable-client", user.userId().toString());

        long version =
                clients.disable(
                        "disable-client", adminId, null, "compromised callback", UUID.randomUUID());

        assertThat(version).isEqualTo(2);
        Long disabled =
                jdbcClient
                        .sql(
                                """
                SELECT COUNT(*) FROM registered_client_metadata
                 WHERE registered_client_id = :id AND status = 'DISABLED'
                """)
                        .param("id", registeredClientId("disable-client"))
                        .query(Long.class)
                        .single();
        assertThat(disabled).isOne();
        Long outbox =
                jdbcClient
                        .sql(
                                """
                SELECT COUNT(*) FROM outbox_events WHERE event_type = 'BACKCHANNEL_LOGOUT_REQUESTED'
                """)
                        .query(Long.class)
                        .single();
        assertThat(outbox).isPositive();

        var authorization = newAuthorization("disable-client", user.userId().toString());
        assertThatThrownBy(() -> authorizationService.save(authorization))
                .isInstanceOf(
                        org.springframework.security.oauth2.core.OAuth2AuthenticationException
                                .class);
    }

    @Test
    void rotateSecretInvalidatesOldSecret() {
        UUID adminId = administrator("ClientAdminRotate");
        var user =
                registrationService.register(
                        "ClientRotateUser", "Client Rotate User", "client rotate passphrase");
        AdminClientService.CreateResult created =
                clients.create(
                        new AdminClientService.CreateRequest(
                                "rotate-client",
                                "Rotate",
                                "FIRST_PARTY",
                                List.of("https://rotate.example/callback"),
                                List.of(),
                                List.of("openid", "profile"),
                                List.of("urn:las:resource:rotate"),
                                null),
                        adminId,
                        null,
                        "r",
                        UUID.randomUUID());
        insertAuthorization("rotate-client", user.userId().toString());

        AdminClientService.RotationResult rotated =
                clients.rotateSecret(
                        "rotate-client", adminId, null, "secret leaked", UUID.randomUUID());

        assertThat(rotated.clientSecret()).isNotEqualTo(created.clientSecret());
        assertThat(rotated.configVersion()).isEqualTo(2);
        String storedSecret =
                jdbcClient
                        .sql(
                                """
                SELECT client_secret FROM oauth2_registered_client WHERE client_id = 'rotate-client'
                """)
                        .query(String.class)
                        .single();
        assertThat(passwordEncoder.matches(rotated.clientSecret(), storedSecret)).isTrue();
        assertThat(passwordEncoder.matches(created.clientSecret(), storedSecret)).isFalse();
        Long remaining =
                jdbcClient
                        .sql(
                                """
                SELECT COUNT(*) FROM oauth2_authorization WHERE registered_client_id = :id
                """)
                        .param("id", registeredClientId("rotate-client"))
                        .query(Long.class)
                        .single();
        assertThat(remaining).isZero();
    }

    @Test
    void clientPatchCannotRestoreSecretAfterConcurrentRotation() throws Exception {
        UUID adminId = administrator("ClientAdminConcurrent");
        AdminClientService.CreateResult created =
                clients.create(
                        new AdminClientService.CreateRequest(
                                "concurrent-client",
                                "Concurrent",
                                "FIRST_PARTY",
                                List.of("https://concurrent.example/callback"),
                                List.of(),
                                List.of("openid", "profile"),
                                List.of("urn:las:resource:concurrent"),
                                null),
                        adminId,
                        null,
                        "r",
                        UUID.randomUUID());
        BlockingRegisteredClientRepository blocking =
                new BlockingRegisteredClientRepository(registeredClients, "concurrent-client");
        AdminClientService stalePatchService =
                new AdminClientService(
                        jdbcClient,
                        blocking,
                        tokenSettings,
                        passwordEncoder,
                        backChannelLogout,
                        uuidGenerator,
                        new SecureRandom(),
                        Clock.systemUTC(),
                        true);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var patch =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            status ->
                                                    stalePatchService.patch(
                                                            "concurrent-client",
                                                            new AdminClientService.PatchRequest(
                                                                    "Edited",
                                                                    "FIRST_PARTY",
                                                                    List.of(
                                                                            "https://concurrent.example/callback"),
                                                                    List.of(),
                                                                    List.of("openid", "profile"),
                                                                    List.of(
                                                                            "urn:las:resource:concurrent"),
                                                                    null),
                                                            adminId,
                                                            null,
                                                            "edit",
                                                            UUID.randomUUID())));
            assertThat(blocking.readStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var rotation =
                    executor.submit(
                            () ->
                                    clients.rotateSecret(
                                            "concurrent-client",
                                            adminId,
                                            null,
                                            "rotate during edit",
                                            UUID.randomUUID()));
            assertThatThrownBy(() -> rotation.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            blocking.releaseRead.countDown();
            patch.get(5, TimeUnit.SECONDS);
            AdminClientService.RotationResult rotated = rotation.get(5, TimeUnit.SECONDS);
            String storedSecret =
                    jdbcClient
                            .sql(
                                    """
                    SELECT client_secret FROM oauth2_registered_client WHERE client_id = 'concurrent-client'
                    """)
                            .query(String.class)
                            .single();
            assertThat(passwordEncoder.matches(rotated.clientSecret(), storedSecret)).isTrue();
            assertThat(passwordEncoder.matches(created.clientSecret(), storedSecret)).isFalse();
        }
    }

    @Test
    void clientsPageRendersForAdmin() throws Exception {
        UUID adminId = administrator("ClientAdminPage");
        clients.create(
                new AdminClientService.CreateRequest(
                        "page-client",
                        "Page Client",
                        "FIRST_PARTY",
                        List.of("https://page.example/callback"),
                        List.of(),
                        List.of("openid", "profile"),
                        List.of("urn:las:resource:page"),
                        null),
                adminId,
                null,
                "r",
                UUID.randomUUID());
        var actor =
                registrationService.register(
                        "ClientPageActor", "Client Page Actor", "client page actor passphrase");
        LasUserPrincipal admin = principal(actor.userId(), "ClientPageActor", true);
        var adminSession = new MockHttpSession();

        mockMvc.perform(get("/admin/clients").session(adminSession).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .string(org.hamcrest.Matchers.containsString("接入应用")))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .string(org.hamcrest.Matchers.containsString("page-client")));
    }

    @Test
    void listFiltersByStatusAndHidesDisabledByDefault() {
        UUID adminId = administrator("ClientAdminFilter");
        clients.create(
                new AdminClientService.CreateRequest(
                        "filter-active",
                        "Filter Active",
                        "FIRST_PARTY",
                        List.of("https://filter-active.example/callback"),
                        List.of(),
                        List.of("openid", "profile"),
                        List.of("urn:las:resource:filter-active"),
                        null),
                adminId,
                null,
                "r",
                UUID.randomUUID());
        clients.create(
                new AdminClientService.CreateRequest(
                        "filter-disabled",
                        "Filter Disabled",
                        "FIRST_PARTY",
                        List.of("https://filter-disabled.example/callback"),
                        List.of(),
                        List.of("openid", "profile"),
                        List.of("urn:las:resource:filter-disabled"),
                        null),
                adminId,
                null,
                "r",
                UUID.randomUUID());
        clients.disable("filter-disabled", adminId, null, "retired", UUID.randomUUID());

        AdminClientService.ClientPage defaults = clients.list(null, null, null, 50);
        assertThat(defaults.items())
                .extracting("clientId")
                .contains("filter-active")
                .doesNotContain("filter-disabled");

        AdminClientService.ClientPage all = clients.list(null, "ALL", null, 50);
        assertThat(all.items()).extracting("clientId").contains("filter-active", "filter-disabled");

        AdminClientService.ClientPage disabled = clients.list(null, "DISABLED", null, 50);
        assertThat(disabled.items())
                .extracting("clientId")
                .contains("filter-disabled")
                .doesNotContain("filter-active");
        assertThat(disabled.items()).allMatch(item -> "DISABLED".equals(item.status()));
    }

    @Test
    void endpointsRequireAdminRoleAndRecentAuthentication() throws Exception {
        var actor =
                registrationService.register(
                        "ClientActorUser", "Client Actor User", "client actor passphrase");
        LasUserPrincipal ordinary = principal(actor.userId(), "ClientActorUser", false);
        LasUserPrincipal admin = principal(actor.userId(), "ClientActorUser", true);
        jdbcClient.sql("UPDATE users SET is_sso_admin = false WHERE is_sso_admin = true").update();
        jdbcClient
                .sql("UPDATE users SET is_sso_admin = true WHERE id = :userId")
                .param("userId", actor.userId())
                .update();

        mockMvc.perform(get("/api/v1/admin/clients").with(user(ordinary)))
                .andExpect(status().isForbidden());

        var loginRequest = new MockHttpServletRequest();
        var adminSession = (MockHttpSession) loginRequest.getSession(true);
        userSessions.registerLogin(admin, loginRequest);
        mockMvc.perform(
                        post("/api/v1/admin/clients")
                                .session(adminSession)
                                .with(user(admin))
                                .with(csrf())
                                .header("Origin", "http://127.0.0.1:9000")
                                .contentType("application/json")
                                .content(
                                        "{\"clientName\":\"x\",\"clientType\":\"FIRST_PARTY\","
                                                + "\"redirectUris\":[\"https://x.example/cb\"],"
                                                + "\"scopes\":[\"openid\",\"profile\"],"
                                                + "\"resourceAudiences\":[\"urn:las:resource:x\"],\"reason\":\"r\"}"))
                .andExpect(status().isForbidden());

        adminSession.setAttribute(RecentAuthenticationService.SESSION_ATTRIBUTE, Instant.now());
        mockMvc.perform(
                        post("/api/v1/admin/clients")
                                .session(adminSession)
                                .with(user(admin))
                                .with(csrf())
                                .header("Origin", "http://127.0.0.1:9000")
                                .contentType("application/json")
                                .content(
                                        "{\"clientName\":\"Endpoint Client\",\"clientType\":\"FIRST_PARTY\","
                                                + "\"redirectUris\":[\"https://endpoint.example/cb\"],"
                                                + "\"scopes\":[\"openid\",\"profile\"],"
                                                + "\"resourceAudiences\":[\"urn:las:resource:endpoint\"],\"reason\":\"r\"}"))
                .andExpect(status().isOk());
    }

    private void insertAuthorization(String clientId, String principalName) {
        RegisteredClient client = registeredClient(clientId);
        jdbcClient
                .sql(
                        """
                INSERT INTO oauth2_authorization
                    (id, registered_client_id, principal_name, authorization_grant_type,
                     authorized_scopes, attributes)
                VALUES (:id, :rcId, :principalName, 'authorization_code',
                        'openid,profile', '{}')
                """)
                .param("id", UUID.randomUUID().toString())
                .param("rcId", client.getId())
                .param("principalName", principalName)
                .update();
    }

    private OAuth2Authorization newAuthorization(String clientId, String principalName) {
        return OAuth2Authorization.withRegisteredClient(registeredClient(clientId))
                .id(UUID.randomUUID().toString())
                .principalName(principalName)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(java.util.Set.of("openid"))
                .build();
    }

    private RegisteredClient registeredClient(String clientId) {
        return clients.findByClientId(clientId);
    }

    private String registeredClientId(String clientId) {
        return registeredClient(clientId).getId();
    }

    private static AdminClientService.CreateRequest withUris(
            AdminClientService.CreateRequest base, List<String> uris) {
        return new AdminClientService.CreateRequest(
                base.clientId(),
                base.clientName(),
                base.clientType(),
                uris,
                base.postLogoutRedirectUris(),
                base.scopes(),
                base.resourceAudiences(),
                base.backchannelLogoutUri());
    }

    private static AdminClientService.CreateRequest withScopes(
            AdminClientService.CreateRequest base, List<String> scopes) {
        return new AdminClientService.CreateRequest(
                base.clientId(),
                base.clientName(),
                base.clientType(),
                base.redirectUris(),
                base.postLogoutRedirectUris(),
                scopes,
                base.resourceAudiences(),
                base.backchannelLogoutUri());
    }

    private static AdminClientService.CreateRequest withAudiences(
            AdminClientService.CreateRequest base, List<String> audiences) {
        return new AdminClientService.CreateRequest(
                base.clientId(),
                base.clientName(),
                base.clientType(),
                base.redirectUris(),
                base.postLogoutRedirectUris(),
                base.scopes(),
                audiences,
                base.backchannelLogoutUri());
    }

    private static AdminClientService.CreateRequest withClientId(
            AdminClientService.CreateRequest base, String clientId) {
        return new AdminClientService.CreateRequest(
                clientId,
                base.clientName(),
                base.clientType(),
                base.redirectUris(),
                base.postLogoutRedirectUris(),
                base.scopes(),
                base.resourceAudiences(),
                base.backchannelLogoutUri());
    }

    private UUID administrator(String username) {
        jdbcClient.sql("UPDATE users SET is_sso_admin = false WHERE is_sso_admin = true").update();
        var account =
                registrationService.register(username, "Client Admin", "client admin passphrase");
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

    private static final class BlockingRegisteredClientRepository
            implements RegisteredClientRepository {
        private final RegisteredClientRepository delegate;
        private final String blockedClientId;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);

        private BlockingRegisteredClientRepository(
                RegisteredClientRepository delegate, String blockedClientId) {
            this.delegate = delegate;
            this.blockedClientId = blockedClientId;
        }

        @Override
        public void save(RegisteredClient registeredClient) {
            delegate.save(registeredClient);
        }

        @Override
        public RegisteredClient findById(String id) {
            return delegate.findById(id);
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            RegisteredClient result = delegate.findByClientId(clientId);
            if (blockedClientId.equals(clientId)) {
                readStarted.countDown();
                try {
                    if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting to release stale client read");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted waiting to release stale client read", exception);
                }
            }
            return result;
        }
    }
}
