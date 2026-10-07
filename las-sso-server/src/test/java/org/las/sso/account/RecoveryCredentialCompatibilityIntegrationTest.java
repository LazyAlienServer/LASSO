package org.las.sso.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.las.sso.admin.AdminRecoveryCredentialService;
import org.las.sso.config.RecoveryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Old plaintext/digests are explicit persisted-format fixtures, never regenerated with the new
 * generator.
 */
@SpringBootTest(
        properties = {
            "las.sso.scheduling.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
        })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class RecoveryCredentialCompatibilityIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AccountRegistrationService registration;
    @Autowired RecoveryCodeService codes;
    @Autowired RecoveryCodeRotationService rotation;
    @Autowired AdminRecoveryCredentialService adminRecovery;
    @Autowired PasswordRecoveryService recovery;
    @Autowired PasswordChangeService passwords;
    @Autowired RecoveryProperties properties;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @ParameterizedTest
    @CsvSource({
        "LEGACY, RECOVERY_CODE",
        "LEGACY, ADMIN_ISSUED",
        "CURRENT, RECOVERY_CODE",
        "CURRENT, ADMIN_ISSUED"
    })
    void bothPersistedFormatsCompleteRecoveryAndRejectCredentialAndSessionReplay(
            Format format, PasswordRecoveryService.CredentialType type) {
        var fixture = fixture(format, type);
        UUID request = UUID.randomUUID();
        var session = start(fixture, fixture.code(), request);
        assertThat(usedAt(fixture)).isNotNull();
        assertThat(encoder.matches(fixture.password(), passwordHash(fixture.userId()))).isTrue();
        UUID replayRequest = UUID.randomUUID();
        assertThatThrownBy(() -> start(fixture, fixture.code(), replayRequest))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("RECOVERY_CREDENTIAL_INVALID");
        if (type == PasswordRecoveryService.CredentialType.ADMIN_ISSUED) {
            assertThat(
                            jdbc.sql(
                                            "SELECT result FROM admin_audit_events WHERE request_id = :request AND action = 'ADMIN_RECOVERY_CREDENTIAL_REJECTED'")
                                    .param("request", replayRequest)
                                    .query(String.class)
                                    .single())
                    .isEqualTo("ALREADY_USED");
        }

        recovery.complete(session.token(), "s6 recovered owner password phrase", UUID.randomUUID());

        assertThat(
                        encoder.matches(
                                "s6 recovered owner password phrase",
                                passwordHash(fixture.userId())))
                .isTrue();
        assertThatThrownBy(() -> start(fixture, fixture.code(), UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("RECOVERY_CREDENTIAL_INVALID");
        assertThatThrownBy(
                        () ->
                                recovery.complete(
                                        session.token(),
                                        "s6 replayed password phrase",
                                        UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("SESSION_EXPIRED");
        assertThat(
                        encoder.matches(
                                "s6 recovered owner password phrase",
                                passwordHash(fixture.userId())))
                .isTrue();
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM security_events WHERE user_id = :id AND event_type = 'PASSWORD_RECOVERED'")
                                .param("id", fixture.userId())
                                .query(Long.class)
                                .single())
                .isOne();
        if (type == PasswordRecoveryService.CredentialType.ADMIN_ISSUED) {
            assertThat(
                            jdbc.sql(
                                            "SELECT request_id FROM admin_audit_events WHERE target_id = :id AND action = 'ADMIN_RECOVERY_CREDENTIAL_USED'")
                                    .param("id", fixture.userId().toString())
                                    .query(UUID.class)
                                    .single())
                    .isEqualTo(request);
        }
    }

    @ParameterizedTest
    @EnumSource(PasswordRecoveryService.CredentialType.class)
    void legacyCaseAndSeparatorNormalizationRemainsCompatible(
            PasswordRecoveryService.CredentialType type) {
        var fixture = fixture(Format.LEGACY, type);

        start(fixture, fixture.code().replace("-", "").toLowerCase(Locale.ROOT), UUID.randomUUID());

        assertThat(usedAt(fixture)).isNotNull();
        assertThat(storedDigest(fixture)).isEqualTo(independentDigest(fixture.code()));
    }

    @ParameterizedTest
    @EnumSource(PasswordRecoveryService.CredentialType.class)
    void revokedLegacyCredentialStaysRejectedAndItsHashAndUsageStayUnchanged(
            PasswordRecoveryService.CredentialType type) {
        var fixture = fixture(Format.LEGACY, type);
        jdbc.sql("UPDATE " + table(type) + " SET revoked_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", fixture.credentialId())
                .update();
        String before = credentialRow(fixture);
        UUID request = UUID.randomUUID();

        assertThatThrownBy(() -> start(fixture, fixture.code(), request))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("RECOVERY_CREDENTIAL_INVALID");

        assertThat(credentialRow(fixture)).isEqualTo(before);
        assertThat(liveSessions(fixture.userId())).isZero();
        if (type == PasswordRecoveryService.CredentialType.ADMIN_ISSUED) {
            assertThat(
                            jdbc.sql(
                                            "SELECT result FROM admin_audit_events WHERE request_id = :request AND action = 'ADMIN_RECOVERY_CREDENTIAL_REJECTED'")
                                    .param("request", request)
                                    .query(String.class)
                                    .single())
                    .isEqualTo("REVOKED");
        }
    }

    @Test
    void expiredLegacyAdministratorCredentialIsNotExtendedAndRejectionAuditHasNoPlaintext() {
        var fixture = fixture(Format.LEGACY, PasswordRecoveryService.CredentialType.ADMIN_ISSUED);
        jdbc.sql(
                        "UPDATE admin_recovery_credentials SET created_at = CURRENT_TIMESTAMP - INTERVAL '31 minutes', "
                                + "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id")
                .param("id", fixture.credentialId())
                .update();
        String before = credentialRow(fixture);
        UUID request = UUID.randomUUID();

        assertThatThrownBy(() -> start(fixture, fixture.code(), request))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("RECOVERY_CREDENTIAL_INVALID");

        assertThat(credentialRow(fixture)).isEqualTo(before);
        String audit =
                jdbc.sql(
                                "SELECT to_jsonb(a)::text FROM admin_audit_events a WHERE request_id = :request")
                        .param("request", request)
                        .query(String.class)
                        .single();
        assertThat(audit)
                .contains("EXPIRED", fixture.credentialId().toString())
                .doesNotContain(fixture.code());
    }

    @Test
    void ordinaryRotationRevokesLegacyBatchAndPersistsTenNewEightyBitDigests() {
        var fixture = fixture(Format.LEGACY, PasswordRecoveryService.CredentialType.RECOVERY_CODE);
        var rotated = rotation.rotate(fixture.userId());

        assertThat(rotated.generation()).isEqualTo(2);
        assertThat(rotated.recoveryCodes())
                .hasSize(10)
                .allMatch(code -> code.matches("LAS-[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){3}"));
        assertThat(rotation.status(fixture.userId()).availableCount()).isEqualTo(10);
        for (String plaintext : rotated.recoveryCodes()) {
            assertThat(
                            jdbc.sql(
                                            "SELECT COUNT(*) FROM recovery_codes WHERE user_id = :id AND code_hash = :digest AND generation = 2 AND revoked_at IS NULL")
                                    .param("id", fixture.userId())
                                    .param("digest", independentDigest(plaintext))
                                    .query(Long.class)
                                    .single())
                    .isOne();
        }
        assertThatThrownBy(() -> start(fixture, fixture.code(), UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class);
        recovery.start(
                fixture.username(),
                rotated.recoveryCodes().getFirst(),
                fixture.type(),
                null,
                "s6-rotated-" + fixture.userId(),
                UUID.randomUUID());
    }

    @Test
    void administratorReissueRevokesLegacyCredentialAndKeepsThirtyMinuteNewLifetime() {
        var fixture = fixture(Format.LEGACY, PasswordRecoveryService.CredentialType.ADMIN_ISSUED);
        var replacement =
                adminRecovery.issue(
                        fixture.actorId(),
                        null,
                        fixture.userId(),
                        "s6 verified reissue",
                        UUID.randomUUID());

        assertThat(replacement.credential())
                .matches("LAS-ADMIN-[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){3}");
        assertThat(replacement.expiresAt())
                .isAfter(OffsetDateTime.now().plusMinutes(29))
                .isBefore(OffsetDateTime.now().plusMinutes(31));
        assertThatThrownBy(() -> start(fixture, fixture.code(), UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class);
        recovery.start(
                fixture.username(),
                replacement.credential(),
                fixture.type(),
                null,
                "s6-reissue-" + fixture.userId(),
                UUID.randomUUID());
    }

    @ParameterizedTest
    @EnumSource(PasswordRecoveryService.CredentialType.class)
    void passwordChangeStillInvalidatesLegacyCredentialAndAnAlreadyCreatedSession(
            PasswordRecoveryService.CredentialType type) {
        var fixture = fixture(Format.LEGACY, type);
        var session = start(fixture, fixture.code(), UUID.randomUUID());

        passwords.changePassword(
                fixture.userId(), false, fixture.password(), "s6 changed owner password phrase");

        assertThatThrownBy(
                        () ->
                                recovery.complete(
                                        session.token(),
                                        "s6 stale completion password phrase",
                                        UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("SESSION_EXPIRED");
        assertThatThrownBy(() -> start(fixture, fixture.code(), UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("RECOVERY_CREDENTIAL_INVALID");
        assertThat(
                        encoder.matches(
                                "s6 changed owner password phrase", passwordHash(fixture.userId())))
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(PasswordRecoveryService.CredentialType.class)
    void passwordChangeRevokesAnUnusedLegacyCredentialBeforeItCanCreateASession(
            PasswordRecoveryService.CredentialType type) {
        var fixture = fixture(Format.LEGACY, type);
        passwords.changePassword(
                fixture.userId(), false, fixture.password(), "s6 unspent change password phrase");

        assertThatThrownBy(() -> start(fixture, fixture.code(), UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("RECOVERY_CREDENTIAL_INVALID");

        assertThat(usedAt(fixture)).isNull();
        assertThat(liveSessions(fixture.userId())).isZero();
        assertThat(
                        encoder.matches(
                                "s6 unspent change password phrase",
                                passwordHash(fixture.userId())))
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(PasswordRecoveryService.CredentialType.class)
    void legacyCreatedSessionKeepsItsTenMinuteExpiryAndCannotChangePasswordAfterExpiration(
            PasswordRecoveryService.CredentialType type) {
        var fixture = fixture(Format.LEGACY, type);
        var session = start(fixture, fixture.code(), UUID.randomUUID());
        assertThat(session.expiresAt())
                .isAfter(OffsetDateTime.now().plusMinutes(9))
                .isBefore(OffsetDateTime.now().plusMinutes(11));
        jdbc.sql(
                        "UPDATE recovery_sessions SET created_at = CURRENT_TIMESTAMP - INTERVAL '11 minutes', "
                                + "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE user_id = :id")
                .param("id", fixture.userId())
                .update();

        assertThatThrownBy(
                        () ->
                                recovery.complete(
                                        session.token(),
                                        "s6 expired session password phrase",
                                        UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class)
                .extracting("code")
                .isEqualTo("SESSION_EXPIRED");
        assertThat(encoder.matches(fixture.password(), passwordHash(fixture.userId()))).isTrue();
    }

    @Test
    void completingLegacyOrdinaryRecoveryPreservesOtherUnusedCodesFromTheSameBatch() {
        var fixture = fixture(Format.LEGACY, PasswordRecoveryService.CredentialType.RECOVERY_CODE);
        String otherOldCode = "LAS-EFGH-JKLM-NPQR";
        UUID otherId =
                jdbc.sql(
                                "SELECT id FROM recovery_codes WHERE user_id = :id AND id <> :first ORDER BY id LIMIT 1")
                        .param("id", fixture.userId())
                        .param("first", fixture.credentialId())
                        .query(UUID.class)
                        .single();
        jdbc.sql("UPDATE recovery_codes SET code_hash = :digest WHERE id = :id")
                .param("digest", independentDigest(otherOldCode))
                .param("id", otherId)
                .update();
        var session = start(fixture, fixture.code(), UUID.randomUUID());

        recovery.complete(session.token(), "s6 preserved batch password phrase", UUID.randomUUID());

        assertThat(rotation.status(fixture.userId()).availableCount()).isEqualTo(9);
        start(fixture, otherOldCode, UUID.randomUUID());
        assertThat(rotation.status(fixture.userId()).availableCount()).isEqualTo(8);
    }

    @Test
    void failedRotationRollsBackLegacyRevocationAndNewBatchBeforeRetry() {
        var fixture = fixture(Format.LEGACY, PasswordRecoveryService.CredentialType.RECOVERY_CODE);
        var before =
                jdbc.sql(
                                "SELECT to_jsonb(c)::text FROM recovery_codes c WHERE user_id = :id ORDER BY id")
                        .param("id", fixture.userId())
                        .query(String.class)
                        .list();

        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                tx -> {
                                                    rotation.rotate(fixture.userId());
                                                    throw new IllegalStateException(
                                                            "s6 rotation rollback fixture");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(
                        jdbc.sql(
                                        "SELECT to_jsonb(c)::text FROM recovery_codes c WHERE user_id = :id ORDER BY id")
                                .param("id", fixture.userId())
                                .query(String.class)
                                .list())
                .isEqualTo(before);
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM security_events WHERE user_id = :id AND event_type = 'RECOVERY_CODES_ROTATED'")
                                .param("id", fixture.userId())
                                .query(Long.class)
                                .single())
                .isZero();
        start(fixture, fixture.code(), UUID.randomUUID());
    }

    @Test
    void failedAdministratorReissueDoesNotRevokeLegacyCredentialOrCommitSuccessAudit() {
        var fixture = fixture(Format.LEGACY, PasswordRecoveryService.CredentialType.ADMIN_ISSUED);
        String before = credentialRow(fixture);
        long audits =
                jdbc.sql("SELECT COUNT(*) FROM admin_audit_events WHERE target_id = :id")
                        .param("id", fixture.userId().toString())
                        .query(Long.class)
                        .single();

        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                tx -> {
                                                    adminRecovery.issue(
                                                            fixture.actorId(),
                                                            null,
                                                            fixture.userId(),
                                                            "s6 rollback reissue",
                                                            UUID.randomUUID());
                                                    throw new IllegalStateException(
                                                            "s6 reissue rollback fixture");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(credentialRow(fixture)).isEqualTo(before);
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM admin_recovery_credentials WHERE target_user_id = :id")
                                .param("id", fixture.userId())
                                .query(Long.class)
                                .single())
                .isOne();
        assertThat(
                        jdbc.sql("SELECT COUNT(*) FROM admin_audit_events WHERE target_id = :id")
                                .param("id", fixture.userId().toString())
                                .query(Long.class)
                                .single())
                .isEqualTo(audits);
        start(fixture, fixture.code(), UUID.randomUUID());
    }

    @ParameterizedTest
    @EnumSource(PasswordRecoveryService.CredentialType.class)
    void oldCredentialCannotBeUsedForAnotherUserOrCredentialType(
            PasswordRecoveryService.CredentialType type) {
        var fixture = fixture(Format.LEGACY, type);
        var other = register("S6Other");
        var wrongType =
                type == PasswordRecoveryService.CredentialType.RECOVERY_CODE
                        ? PasswordRecoveryService.CredentialType.ADMIN_ISSUED
                        : PasswordRecoveryService.CredentialType.RECOVERY_CODE;

        assertThatThrownBy(
                        () ->
                                recovery.start(
                                        other.username(),
                                        fixture.code(),
                                        type,
                                        null,
                                        "s6-other-" + other.userId(),
                                        UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class);
        assertThatThrownBy(
                        () ->
                                recovery.start(
                                        fixture.username(),
                                        fixture.code(),
                                        wrongType,
                                        null,
                                        "s6-type-" + fixture.userId(),
                                        UUID.randomUUID()))
                .isInstanceOf(RecoveryFlowException.class);
        assertThat(usedAt(fixture)).isNull();
        start(fixture, fixture.code(), UUID.randomUUID());
    }

    @ParameterizedTest
    @EnumSource(PasswordRecoveryService.CredentialType.class)
    void legacyCompatibilityDoesNotBypassExistingRecoveryCaptchaAndUserAttemptLimits(
            PasswordRecoveryService.CredentialType type) {
        var fixture = fixture(Format.LEGACY, type);
        String before = credentialRow(fixture);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> start(fixture, fixture.code() + "Z", UUID.randomUUID()))
                    .isInstanceOf(RecoveryFlowException.class)
                    .extracting("code")
                    .isEqualTo("RECOVERY_CREDENTIAL_INVALID");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> start(fixture, fixture.code(), UUID.randomUUID()))
                    .isInstanceOf(RecoveryFlowException.class)
                    .extracting("code")
                    .isEqualTo("CAPTCHA_REQUIRED");
        }
        assertThatThrownBy(() -> start(fixture, fixture.code(), UUID.randomUUID()))
                .isInstanceOf(org.las.sso.security.RateLimitExceededException.class);
        assertThat(credentialRow(fixture)).isEqualTo(before);
        assertThat(liveSessions(fixture.userId())).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "LEGACY, RECOVERY_CODE",
        "LEGACY, ADMIN_ISSUED",
        "CURRENT, RECOVERY_CODE",
        "CURRENT, ADMIN_ISSUED"
    })
    void existingRecoveryHttpContractAcceptsBothFormatsAndKeepsSessionTokenInSecureCookie(
            Format format, PasswordRecoveryService.CredentialType type) throws Exception {
        var fixture = fixture(format, type);
        var response =
                mvc.perform(
                                post("/api/v1/recovery/sessions")
                                        .with(csrf())
                                        .header("Origin", "http://127.0.0.1:9000")
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        java.util.Map.of(
                                                                "username",
                                                                fixture.username(),
                                                                "credential",
                                                                fixture.code(),
                                                                "credentialType",
                                                                type.name()))))
                        .andExpect(status().isNoContent())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                        .getResponse();
        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie)
                .contains(
                        "__Host-las_recovery=",
                        "Secure",
                        "HttpOnly",
                        "SameSite=Strict",
                        "Max-Age=600");
        assertThat(response.getContentAsString()).isEmpty();
        String token = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));

        mvc.perform(
                        put("/api/v1/recovery/password")
                                .with(csrf())
                                .cookie(new Cookie("__Host-las_recovery", token))
                                .header("Origin", "http://127.0.0.1:9000")
                                .contentType("application/json")
                                .content("{\"newPassword\":\"s6 http recovered password phrase\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(
                        encoder.matches(
                                "s6 http recovered password phrase",
                                passwordHash(fixture.userId())))
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(PasswordRecoveryService.CredentialType.class)
    void concurrentLegacyConsumptionStillAllowsOnlyOneSession(
            PasswordRecoveryService.CredentialType type) throws Exception {
        var fixture = fixture(Format.LEGACY, type);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return outcome(fixture);
                            });
            var second =
                    executor.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return outcome(fixture);
                            });
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "RECOVERY_CREDENTIAL_INVALID");
        }
        assertThat(liveSessions(fixture.userId())).isOne();
    }

    @ParameterizedTest
    @EnumSource(PasswordRecoveryService.CredentialType.class)
    void rollbackOfLegacyConsumptionRestoresCredentialAndAllowsRetry(
            PasswordRecoveryService.CredentialType type) {
        var fixture = fixture(Format.LEGACY, type);
        String before = credentialRow(fixture);

        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                tx -> {
                                                    start(
                                                            fixture,
                                                            fixture.code(),
                                                            UUID.randomUUID());
                                                    throw new IllegalStateException(
                                                            "s6 fixture rollback");
                                                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(credentialRow(fixture)).isEqualTo(before);
        assertThat(liveSessions(fixture.userId())).isZero();
        start(fixture, fixture.code(), UUID.randomUUID());
    }

    @Test
    void recoveryPageShowsLongerFormatWithoutRejectingLegacyInputLength() throws Exception {
        String html =
                mvc.perform(get("/password-recovery"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html)
                .contains(
                        "LAS-XXXX-XXXX-XXXX-XXXX",
                        "LAS-ADMIN-XXXX-XXXX-XXXX-XXXX",
                        "maxlength=\"64\"")
                .doesNotContain("pattern=\"LAS-");
    }

    private Fixture fixture(Format format, PasswordRecoveryService.CredentialType type) {
        var target = register("S6Target");
        String plaintext = target.recoveryCodes().getFirst();
        UUID actorId = null;
        UUID credentialId;
        if (type == PasswordRecoveryService.CredentialType.ADMIN_ISSUED) {
            actorId = register("S6Actor").userId();
            plaintext =
                    adminRecovery
                            .issue(
                                    actorId,
                                    null,
                                    target.userId(),
                                    "s6 fixture identity verified",
                                    UUID.randomUUID())
                            .credential();
            credentialId =
                    jdbc.sql(
                                    "SELECT id FROM admin_recovery_credentials WHERE target_user_id = :id AND revoked_at IS NULL")
                            .param("id", target.userId())
                            .query(UUID.class)
                            .single();
        } else {
            credentialId =
                    jdbc.sql(
                                    "SELECT id FROM recovery_codes WHERE user_id = :id AND code_hash = :hash")
                            .param("id", target.userId())
                            .param("hash", independentDigest(plaintext))
                            .query(UUID.class)
                            .single();
        }
        if (format == Format.LEGACY) {
            // Replace a genuine owning-service row's digest with an independently computed
            // historical-format fixture.
            plaintext =
                    type == PasswordRecoveryService.CredentialType.ADMIN_ISSUED
                            ? "LAS-ADMIN-EFGH-JKLM-NPQR"
                            : "LAS-2345-6789-ABCD";
            jdbc.sql(
                            "UPDATE "
                                    + table(type)
                                    + " SET "
                                    + hashColumn(type)
                                    + " = :digest WHERE id = :id")
                    .param("digest", independentDigest(plaintext))
                    .param("id", credentialId)
                    .update();
        } else {
            assertThat(plaintext)
                    .matches(
                            type == PasswordRecoveryService.CredentialType.ADMIN_ISSUED
                                    ? "LAS-ADMIN-[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){3}"
                                    : "LAS-[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){3}");
        }
        return new Fixture(
                target.userId(),
                target.username(),
                "s6 initial owner password phrase",
                plaintext,
                type,
                credentialId,
                actorId);
    }

    private AccountRegistrationService.RegistrationResult register(String prefix) {
        return registration.register(
                prefix + UUID.randomUUID().toString().substring(0, 8),
                "S6 fixture",
                "s6 initial owner password phrase");
    }

    private PasswordRecoveryService.RecoverySession start(
            Fixture fixture, String code, UUID request) {
        return recovery.start(
                fixture.username(),
                code,
                fixture.type(),
                null,
                "s6-network-" + fixture.userId(),
                request);
    }

    private String outcome(Fixture fixture) {
        try {
            start(fixture, fixture.code(), UUID.randomUUID());
            return "SUCCESS";
        } catch (RecoveryFlowException exception) {
            return exception.code();
        }
    }

    private byte[] independentDigest(String code) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            properties.pepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(
                    code.replace("-", "")
                            .toUpperCase(Locale.ROOT)
                            .getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] storedDigest(Fixture fixture) {
        return jdbc.sql(
                        "SELECT "
                                + hashColumn(fixture.type())
                                + " FROM "
                                + table(fixture.type())
                                + " WHERE id = :id")
                .param("id", fixture.credentialId())
                .query(byte[].class)
                .single();
    }

    private OffsetDateTime usedAt(Fixture fixture) {
        return jdbc.sql("SELECT used_at FROM " + table(fixture.type()) + " WHERE id = :id")
                .param("id", fixture.credentialId())
                .query((rs, row) -> new Usage(rs.getObject("used_at", OffsetDateTime.class)))
                .single()
                .usedAt();
    }

    private String credentialRow(Fixture fixture) {
        return jdbc.sql(
                        "SELECT to_jsonb(c)::text FROM "
                                + table(fixture.type())
                                + " c WHERE id = :id")
                .param("id", fixture.credentialId())
                .query(String.class)
                .single();
    }

    private long liveSessions(UUID id) {
        return jdbc.sql(
                        "SELECT COUNT(*) FROM recovery_sessions WHERE user_id = :id AND consumed_at IS NULL")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private String passwordHash(UUID id) {
        return jdbc.sql("SELECT password_hash FROM password_credentials WHERE user_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private static String table(PasswordRecoveryService.CredentialType type) {
        return type == PasswordRecoveryService.CredentialType.ADMIN_ISSUED
                ? "admin_recovery_credentials"
                : "recovery_codes";
    }

    private static String hashColumn(PasswordRecoveryService.CredentialType type) {
        return type == PasswordRecoveryService.CredentialType.ADMIN_ISSUED
                ? "credential_hash"
                : "code_hash";
    }

    private enum Format {
        LEGACY,
        CURRENT
    }

    private record Usage(OffsetDateTime usedAt) {}

    private record Fixture(
            UUID userId,
            String username,
            String password,
            String code,
            PasswordRecoveryService.CredentialType type,
            UUID credentialId,
            UUID actorId) {}
}
