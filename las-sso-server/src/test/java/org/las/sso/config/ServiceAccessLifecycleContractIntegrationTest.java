package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.access.ServiceAccessEligibilityService;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.account.DeletionRequestService;
import org.las.sso.account.PasswordChangeService;
import org.las.sso.account.PasswordRecoveryService;
import org.las.sso.admin.AdminClientService;
import org.las.sso.admin.AdminDeletionRequestService;
import org.las.sso.admin.AdminUserManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Actual service/wire lifecycle and recovery-generation fixtures; no real backup restore is
 * implied.
 */
@SpringBootTest(
        properties = {"las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class ServiceAccessLifecycleContractIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService accounts;
    @Autowired AdminClientService clients;
    @Autowired AdminUserManagementService users;
    @Autowired AdminDeletionRequestService adminDeletion;
    @Autowired DeletionRequestService deletion;
    @Autowired PasswordChangeService passwords;
    @Autowired PasswordRecoveryService recovery;
    @Autowired ServiceAccessEligibilityService access;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    private UUID actor;
    private AdminClientService.CreateResult client;

    @BeforeEach
    void firstPartyFixture() {
        actor = register().userId();
        client =
                clients.create(
                        new AdminClientService.CreateRequest(
                                "contract-" + UUID.randomUUID(),
                                "Contract client",
                                "FIRST_PARTY",
                                List.of("https://contract.example/callback"),
                                List.of(),
                                List.of("openid"),
                                List.of("urn:las:contract"),
                                null),
                        actor,
                        null,
                        "fixture client",
                        UUID.randomUUID());
    }

    @Test
    void committedLifecycleTransitionsExposeExactReasonsAndIncreasingVersionsThroughTheWire()
            throws Exception {
        var account = register();
        var active = wire(account.userId());
        assertDecision(active, "ALLOWED", "ACTIVE", 1);
        users.freeze(actor, null, account.userId(), "contract freeze", UUID.randomUUID());
        assertDecision(wire(account.userId()), "DENIED", "ACCOUNT_FROZEN", 2);
        users.unfreeze(actor, null, account.userId(), "contract unfreeze", UUID.randomUUID());
        assertDecision(wire(account.userId()), "ALLOWED", "ACTIVE", 3);
        deletion.request(account.userId(), UUID.randomUUID());
        assertDecision(wire(account.userId()), "DENIED", "DELETION_PENDING", 4);
        UUID request = request(account.userId());
        adminDeletion.hold(
                actor,
                null,
                request,
                "contract hold",
                "fixture review",
                OffsetDateTime.now().plusDays(1),
                UUID.randomUUID());
        assertDecision(wire(account.userId()), "DENIED", "DELETION_HELD", 5);
        adminDeletion.release(actor, null, request, "contract release", UUID.randomUUID());
        assertDecision(wire(account.userId()), "DENIED", "DELETION_PENDING", 6);
        deletion.cancel(account.userId(), UUID.randomUUID());
        assertDecision(wire(account.userId()), "ALLOWED", "ACTIVE", 7);
        deletion.request(account.userId(), UUID.randomUUID());
        due(account.userId());
        deletion.execute(request(account.userId()), account.userId());
        assertDecision(wire(account.userId()), "DENIED", "ACCOUNT_DELETED", 9);
        assertThat(
                        jdbc.sql("SELECT COUNT(*) FROM password_credentials WHERE user_id = :id")
                                .param("id", account.userId())
                                .query(Long.class)
                                .single())
                .isZero();
        assertDecision(wire(account.userId()), "DENIED", "ACCOUNT_DELETED", 9);
        assertDecision(wire(UUID.randomUUID()), "DENIED", "PRINCIPAL_NOT_FOUND", 0);
    }

    @ParameterizedTest
    @CsvSource({
        "FROZEN,PENDING,ACCOUNT_FROZEN",
        "FROZEN,HELD,ACCOUNT_FROZEN",
        "DELETED,HELD,ACCOUNT_DELETED",
        "ACTIVE,PENDING,DELETION_PENDING",
        "ACTIVE,HELD,DELETION_HELD"
    })
    void accountLifecyclePrecedenceRemainsExplicitWhenCredentialOrDeletionRestrictionsCoexist(
            String accountStatus, String deletionStatus, String reason) {
        var account = register();
        deletion.request(account.userId(), UUID.randomUUID());
        if (deletionStatus.equals("HELD"))
            adminDeletion.hold(
                    actor,
                    null,
                    request(account.userId()),
                    "fixture",
                    "fixture",
                    OffsetDateTime.now().plusDays(1),
                    UUID.randomUUID());
        // Deliberate coexistence fixture, not an accepted deleted-plus-held writer flow.
        jdbc.sql(
                        "UPDATE users SET account_status = CAST(:state AS account_status), deleted_at = CASE WHEN :state = 'DELETED' THEN CURRENT_TIMESTAMP ELSE NULL END WHERE id = :id")
                .param("state", accountStatus)
                .param("id", account.userId())
                .update();
        jdbc.sql(
                        "UPDATE password_credentials SET state = 'PASSWORD_CHANGE_REQUIRED' WHERE user_id = :id")
                .param("id", account.userId())
                .update();
        assertThat(access.evaluate(account.userId()).decision()).isEqualTo("DENIED");
        assertThat(access.evaluate(account.userId()).reason()).isEqualTo(reason);
    }

    @ParameterizedTest
    @CsvSource({
        "PASSWORD_CHANGE_REQUIRED,PASSWORD_CHANGE_REQUIRED",
        "DISABLED,CREDENTIALS_DISABLED",
        "MISSING,ACCOUNT_STATE_INVALID"
    })
    void credentialRestrictionsNeverInventAllowedOrAbsentIdentity(String state, String reason)
            throws Exception {
        var account = register();
        if (state.equals("MISSING"))
            jdbc.sql("DELETE FROM password_credentials WHERE user_id = :id")
                    .param("id", account.userId())
                    .update();
        else
            jdbc.sql(
                            "UPDATE password_credentials SET state = CAST(:state AS credential_state) WHERE user_id = :id")
                    .param("state", state)
                    .param("id", account.userId())
                    .update();
        assertDecision(wire(account.userId()), "DENIED", reason, 1);
    }

    @Test
    void rejectedAndRolledBackMutationsDoNotPublishAnotherStateVersionOrChangedTime() {
        var account = register();
        var before = access.evaluate(account.userId());
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                tx -> {
                                                    users.freeze(
                                                            actor,
                                                            null,
                                                            account.userId(),
                                                            "rollback fixture",
                                                            UUID.randomUUID());
                                                    throw new IllegalStateException(
                                                            "contract rollback");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(access.evaluate(account.userId())).isEqualTo(before);
        assertThatThrownBy(
                        () ->
                                users.unfreeze(
                                        actor,
                                        null,
                                        account.userId(),
                                        "invalid transition",
                                        UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);
        assertThat(access.evaluate(account.userId())).isEqualTo(before);
    }

    @Test
    void
            ordinaryPasswordChangeAndRecoveryAdvanceCredentialVersionButDoNotChangeAllowedEligibilityFacts() {
        var account = register();
        var before = access.evaluate(account.userId());
        passwords.changePassword(
                account.userId(),
                false,
                "contract owner password phrase",
                "contract changed password phrase");
        assertThat(access.evaluate(account.userId())).isEqualTo(before);
        var recovered = register();
        var recoveredBefore = access.evaluate(recovered.userId());
        var session =
                recovery.start(
                        recovered.username(),
                        recovered.recoveryCodes().getFirst(),
                        PasswordRecoveryService.CredentialType.RECOVERY_CODE,
                        null,
                        "contract-" + recovered.userId(),
                        UUID.randomUUID());
        recovery.complete(session.token(), "contract recovered password phrase", UUID.randomUUID());
        assertThat(access.evaluate(recovered.userId())).isEqualTo(recoveredBefore);
        assertThat(
                        jdbc.sql(
                                        "SELECT credentials_version FROM password_credentials WHERE user_id = :id")
                                .param("id", recovered.userId())
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void leavingRequiredPasswordStateAdvancesEligibilityAndPreservesExactNoStoreWireShape()
            throws Exception {
        var account = register();
        jdbc.sql(
                        "UPDATE password_credentials SET state = 'PASSWORD_CHANGE_REQUIRED', temporary_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE user_id = :id")
                .param("id", account.userId())
                .update();
        passwords.changePassword(
                account.userId(),
                true,
                "contract owner password phrase",
                "contract required password phrase");
        assertDecision(wire(account.userId()), "ALLOWED", "ACTIVE", 2);
    }

    @Test
    void concurrentQueryDuringUncommittedFreezeSeesCommittedFactsAndAfterCommitSeesNewVersion()
            throws Exception {
        var account = register();
        var before = access.evaluate(account.userId());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            tx -> {
                                users.freeze(
                                        actor,
                                        null,
                                        account.userId(),
                                        "concurrent fixture",
                                        UUID.randomUUID());
                                try {
                                    assertThat(
                                                    executor.submit(
                                                                    () ->
                                                                            access.evaluate(
                                                                                    account
                                                                                            .userId()))
                                                            .get(5, TimeUnit.SECONDS))
                                            .isEqualTo(before);
                                } catch (Exception exception) {
                                    throw new AssertionError(exception);
                                }
                            });
        }
        assertDecision(wire(account.userId()), "DENIED", "ACCOUNT_FROZEN", 2);
    }

    private AccountRegistrationService.RegistrationResult register() {
        return accounts.register(
                "Contract" + UUID.randomUUID().toString().substring(0, 8),
                "Contract fixture",
                "contract owner password phrase");
    }

    private UUID request(UUID id) {
        return jdbc.sql(
                        "SELECT id FROM account_deletion_requests WHERE user_id = :id AND status IN ('PENDING','HELD')")
                .param("id", id)
                .query(UUID.class)
                .single();
    }

    private void due(UUID id) {
        jdbc.sql(
                        "UPDATE account_deletion_requests SET requested_at = CURRENT_TIMESTAMP - INTERVAL '31 days', execute_after = CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE user_id = :id AND status = 'PENDING'")
                .param("id", id)
                .update();
    }

    private tools.jackson.databind.JsonNode wire(UUID id) throws Exception {
        var response =
                mvc.perform(
                                get("/internal/v1/principals/{id}/service-access", id)
                                        .with(httpBasic(client.clientId(), client.clientSecret())))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var document = json.readTree(response.getContentAsString());
        assertThat(
                        document.properties().stream()
                                .map(entry -> entry.getKey())
                                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(
                        Set.of(
                                "issuer",
                                "subject",
                                "decision",
                                "reason",
                                "stateEpoch",
                                "stateVersion",
                                "changedAt"));
        assertThat(document.get("stateEpoch").asText()).isEqualTo(epoch());
        assertThat(document.get("subject").asText()).isEqualTo(id.toString());
        assertThat(document.get("issuer").asText()).isEqualTo("http://127.0.0.1:9000");
        return document;
    }

    private void assertDecision(
            tools.jackson.databind.JsonNode document,
            String decision,
            String reason,
            long version) {
        assertThat(document.get("decision").asText()).isEqualTo(decision);
        assertThat(document.get("reason").asText()).isEqualTo(reason);
        assertThat(document.get("stateVersion").asLong()).isEqualTo(version);
        if (version == 0) assertThat(document.get("changedAt").isNull()).isTrue();
        else assertThat(OffsetDateTime.parse(document.get("changedAt").asText())).isNotNull();
    }

    @Test
    void
            restoredLowerVersionIsDistinguishableOnlyAfterANewSecurityEpochAndOldResponsesAreNotComparable()
                    throws Exception {
        var account = register();
        users.freeze(actor, null, account.userId(), "before recovery", UUID.randomUUID());
        var before = wire(account.userId());
        String oldEpoch = epoch();
        String newEpoch = UUID.randomUUID().toString();
        try {
            // Isolated restoration-shape fixture, not an actual backup/restore/maintenance
            // rehearsal.
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            tx -> {
                                jdbc.sql(
                                                "UPDATE system_security_state SET security_epoch = :epoch, updated_at = CURRENT_TIMESTAMP, change_reason = 'TEST_RESTORE' WHERE id = 1")
                                        .param("epoch", newEpoch)
                                        .update();
                                jdbc.sql(
                                                "UPDATE users SET account_status = 'ACTIVE', frozen_at = NULL, service_access_state_version = 1 WHERE id = :id")
                                        .param("id", account.userId())
                                        .update();
                            });
            var after = wire(account.userId());
            assertDecision(after, "ALLOWED", "ACTIVE", 1);
            assertThat(after.get("stateEpoch").asText())
                    .isNotEqualTo(before.get("stateEpoch").asText());
            assertThat(after.get("stateVersion").asLong())
                    .isLessThan(before.get("stateVersion").asLong());
            assertThat(wire(UUID.randomUUID()).get("stateEpoch").asText()).isEqualTo(newEpoch);
        } finally {
            jdbc.sql("UPDATE system_security_state SET security_epoch = :epoch WHERE id = 1")
                    .param("epoch", oldEpoch)
                    .update();
        }
    }

    @Test
    void rolledBackEpochChangeDoesNotLeakAnUncommittedRecoveryGeneration() {
        var account = register();
        var before = access.evaluate(account.userId());
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx -> {
                            jdbc.sql(
                                            "UPDATE system_security_state SET security_epoch = :epoch WHERE id = 1")
                                    .param("epoch", UUID.randomUUID().toString())
                                    .update();
                            tx.setRollbackOnly();
                        });
        assertThat(access.evaluate(account.userId())).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "blank"})
    void absentOrBlankEpochFailsClosedWithUnavailableRatherThanAllowedOrUnknown(String fault)
            throws Exception {
        var account = register();
        var original =
                jdbc.sql("SELECT to_jsonb(s)::text FROM system_security_state s WHERE id = 1")
                        .query(String.class)
                        .single();
        String oldEpoch = epoch();
        try {
            if (fault.equals("missing"))
                jdbc.sql("DELETE FROM system_security_state WHERE id = 1").update();
            else
                jdbc.sql("UPDATE system_security_state SET security_epoch = '' WHERE id = 1")
                        .update();
            for (UUID subject : List.of(account.userId(), UUID.randomUUID())) {
                var response =
                        mvc.perform(
                                        get("/internal/v1/principals/{id}/service-access", subject)
                                                .with(
                                                        httpBasic(
                                                                client.clientId(),
                                                                client.clientSecret())))
                                .andExpect(status().isServiceUnavailable())
                                .andReturn()
                                .getResponse();
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(json.readTree(response.getContentAsString()).get("code").asText())
                        .isEqualTo("IDENTITY_SERVICE_UNAVAILABLE");
            }
        } finally {
            if (fault.equals("missing"))
                jdbc.sql(
                                "INSERT INTO system_security_state SELECT * FROM jsonb_populate_record(NULL::system_security_state, CAST(:snapshot AS jsonb))")
                        .param("snapshot", original)
                        .update();
            else
                jdbc.sql("UPDATE system_security_state SET security_epoch = :epoch WHERE id = 1")
                        .param("epoch", oldEpoch)
                        .update();
        }
    }

    private String epoch() {
        return jdbc.sql("SELECT security_epoch FROM system_security_state WHERE id = 1")
                .query(String.class)
                .single();
    }
}
