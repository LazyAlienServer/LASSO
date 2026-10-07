package org.las.sso.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.account.DeletionRequestService;
import org.las.sso.admin.AdminUserManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        properties = {"las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ServiceAccessEligibilityIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService registrationService;

    @Autowired DeletionRequestService deletions;

    @Autowired AdminUserManagementService users;

    @Autowired MockMvc mockMvc;

    @Test
    void allowsActiveSubjectAndReturnsNoStoreVersionedResponse() throws Exception {
        var account = register("AccessAllowed");

        query(account.userId())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.issuer").value("http://127.0.0.1:9000"))
                .andExpect(jsonPath("$.subject").value(account.userId().toString()))
                .andExpect(jsonPath("$.decision").value("ALLOWED"))
                .andExpect(jsonPath("$.reason").value("ACTIVE"))
                .andExpect(jsonPath("$.stateVersion").value(1));
    }

    @Test
    void returnsExplicitDeniedForUnknownSubject() throws Exception {
        query(UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("DENIED"))
                .andExpect(jsonPath("$.reason").value("PRINCIPAL_NOT_FOUND"))
                .andExpect(jsonPath("$.stateVersion").value(0));
    }

    @Test
    void rejectsMissingOrInvalidFirstPartyCredentials() throws Exception {
        var account = register("AccessAuth");

        mockMvc.perform(get(path(account.userId())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"LAS SSO internal\""));

        mockMvc.perform(get(path(account.userId())).with(httpBasic("laslog-bff", "wrong-secret")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void encodedInternalPathCannotBypassFirstPartyAuthentication() throws Exception {
        var account = register("EncPath");

        mockMvc.perform(
                        get(
                                URI.create(
                                        "/internal/v1/%70rincipals/"
                                                + account.userId()
                                                + "/service-access")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"LAS SSO internal\""));

        mockMvc.perform(
                        get(URI.create(
                                        "/internal/v1/%70rincipals/"
                                                + account.userId()
                                                + "/service-access"))
                                .with(httpBasic("laslog-bff", "laslog-local-only")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(account.userId().toString()))
                .andExpect(jsonPath("$.decision").value("ALLOWED"));
    }

    @Test
    void incrementsVersionWhenDeletionEligibilityChanges() throws Exception {
        var account = register("AccessDeletion");

        query(account.userId())
                .andExpect(jsonPath("$.decision").value("ALLOWED"))
                .andExpect(jsonPath("$.stateVersion").value(1));

        deletions.request(account.userId(), UUID.randomUUID());
        query(account.userId())
                .andExpect(jsonPath("$.decision").value("DENIED"))
                .andExpect(jsonPath("$.reason").value("DELETION_PENDING"))
                .andExpect(jsonPath("$.stateVersion").value(2));

        deletions.cancel(account.userId(), UUID.randomUUID());
        query(account.userId())
                .andExpect(jsonPath("$.decision").value("ALLOWED"))
                .andExpect(jsonPath("$.reason").value("ACTIVE"))
                .andExpect(jsonPath("$.stateVersion").value(3));
    }

    @Test
    void incrementsVersionForFreezeAndUnfreeze() throws Exception {
        var account = register("AccessFreeze");
        UUID actor = UUID.randomUUID();

        users.freeze(actor, null, account.userId(), "test freeze", UUID.randomUUID());
        query(account.userId())
                .andExpect(jsonPath("$.decision").value("DENIED"))
                .andExpect(jsonPath("$.reason").value("ACCOUNT_FROZEN"))
                .andExpect(jsonPath("$.stateVersion").value(2));

        users.unfreeze(actor, null, account.userId(), "test unfreeze", UUID.randomUUID());
        query(account.userId())
                .andExpect(jsonPath("$.decision").value("ALLOWED"))
                .andExpect(jsonPath("$.reason").value("ACTIVE"))
                .andExpect(jsonPath("$.stateVersion").value(3));
    }

    private AccountRegistrationService.RegistrationResult register(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return registrationService.register(
                prefix + suffix, prefix + " Display", "correct horse battery staple " + suffix);
    }

    private ResultActions query(UUID subject) throws Exception {
        return mockMvc.perform(
                get(path(subject)).with(httpBasic("laslog-bff", "laslog-local-only")));
    }

    private static String path(UUID subject) {
        return "/internal/v1/principals/" + subject + "/service-access";
    }
}
