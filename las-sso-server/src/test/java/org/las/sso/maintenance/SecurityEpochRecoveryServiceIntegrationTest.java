package org.las.sso.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.account.AccountRegistrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real transaction/schema tests; fixtures are not backup files or a consumer cache implementation. */
@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SecurityEpochRecoveryServiceIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");
    @Autowired JdbcClient jdbc;
    @Autowired SecurityEpochRecoveryService recovery;
    @Autowired AccountRegistrationService accounts;
    private UUID user;

    @BeforeEach void cleanAndSeed() {
        for (String table : List.of("security_events", "oidc_password_challenges", "oauth2_authorization", "oauth2_authorization_consent",
                "oauth2_refresh_token_history", "user_sessions", "spring_session", "outbox_events", "rate_limit_buckets",
                "captcha_challenges", "recovery_sessions", "registration_idempotency", "operation_idempotency")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE system_security_state SET security_epoch='fixture-epoch', updated_at=CURRENT_TIMESTAMP, change_reason='TEST' WHERE id=1").update();
        user = accounts.register("Restore" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "Restore fixture", "isolated recovery fixture passphrase").userId();
        jdbc.sql("""
                INSERT INTO user_sessions(id,sid,user_id,status,remembered,admin_session,credentials_version,security_epoch,
                    created_at,last_seen_at,idle_expires_at,absolute_expires_at,last_password_auth_at)
                VALUES (:id,'restore-sid',:user,'ACTIVE',false,false,1,'fixture-epoch',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP+INTERVAL '1 hour',CURRENT_TIMESTAMP+INTERVAL '1 day',CURRENT_TIMESTAMP)
                """).param("id", UUID.randomUUID()).param("user", user).update();
        String primary = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO spring_session VALUES (:primary,:session,1,1,3600,3600000,:user)
                """).param("primary", primary).param("session", UUID.randomUUID().toString()).param("user", user.toString()).update();
        jdbc.sql("INSERT INTO spring_session_attributes VALUES (:primary,'fixture',decode('ff','hex'))").param("primary", primary).update();
        jdbc.sql("""
                INSERT INTO oauth2_registered_client(id,client_id,client_name,client_authentication_methods,
                    authorization_grant_types,scopes,client_settings,token_settings)
                VALUES ('restore-client','restore-client','Restore client','client_secret_basic','authorization_code','openid','{}','{}')
                ON CONFLICT (id) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO oauth2_authorization(id,registered_client_id,principal_name,authorization_grant_type)
                VALUES ('restore-auth','restore-client',:user,'authorization_code')
                """).param("user", user.toString()).update();
        jdbc.sql("""
                INSERT INTO oauth2_authorization_security(authorization_id,credentials_version,security_epoch,client_config_version,sid)
                VALUES ('restore-auth',1,'fixture-epoch',1,'restore-sid')
                """).update();
        jdbc.sql("INSERT INTO oauth2_authorization_consent VALUES ('restore-client',:user,'SCOPE_openid')").param("user", user.toString()).update();
        jdbc.sql("""
                INSERT INTO oauth2_refresh_token_history(token_hash,token_family_id,authorization_id,status,issued_at,expires_at)
                VALUES (decode('a1','hex'),:family,'restore-auth','CURRENT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 day'),
                       (decode('a2','hex'),:orphan,'orphan-auth','CURRENT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 day')
                """).param("family", UUID.randomUUID()).param("orphan", UUID.randomUUID()).update();
        jdbc.sql("""
                INSERT INTO oidc_password_challenges(id,expires_at,completed_at,subject,sid)
                VALUES (:pending,CURRENT_TIMESTAMP+INTERVAL '5 minutes',NULL,NULL,NULL),
                       (:completed,CURRENT_TIMESTAMP+INTERVAL '5 minutes',CURRENT_TIMESTAMP,:user,'restore-sid')
                """).param("pending", UUID.randomUUID()).param("completed", UUID.randomUUID()).param("user", user).update();
        jdbc.sql("""
                INSERT INTO rate_limit_buckets VALUES ('RESTORE',decode('a1','hex'),CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP+INTERVAL '1 hour',1,CURRENT_TIMESTAMP)
                """).update();
        jdbc.sql("""
                INSERT INTO captcha_challenges(id,purpose,challenge_digest,created_at,expires_at)
                VALUES (:id,'REGISTER',decode('a1','hex'),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '5 minutes')
                """).param("id", UUID.randomUUID()).update();
        jdbc.sql("""
                INSERT INTO recovery_sessions(id,user_id,session_token_hash,credential_type,created_at,expires_at)
                VALUES (:id,:user,decode('a1','hex'),'RECOVERY_CODE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '10 minutes')
                """).param("id", UUID.randomUUID()).param("user", user).update();
        jdbc.sql("INSERT INTO registration_idempotency VALUES (:id,decode('a1','hex'),:user,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 day')")
                .param("id", UUID.randomUUID()).param("user", user).update();
        jdbc.sql("""
                INSERT INTO operation_idempotency(actor_user_id,operation,idempotency_key,request_hash,created_at,expires_at)
                VALUES (:user,'RESTORE','fixture',decode('a1','hex'),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 day')
                """).param("user", user).update();
        jdbc.sql("""
                INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload_json,created_at,available_at)
                VALUES (:id,'USER',:user,'GLOBAL_LOGOUT_REQUESTED','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).param("id", UUID.randomUUID()).param("user", user.toString()).update();
    }

    @Test void clearsAllRestoredLoginTransactionsRevokesFamiliesAndPreservesDurableFactsConsentAndOutbox() {
        String facts = permanentSnapshot();
        var result = recovery.rotateAfterRestore("fixture-epoch", "ISOLATED_RESTORE_DRILL");
        assertThat(result.oldEpoch()).isEqualTo("fixture-epoch");
        assertThat(result.newEpoch()).isNotBlank().isNotEqualTo("fixture-epoch");
        assertThat(epoch()).isEqualTo(result.newEpoch());
        assertThat(permanentSnapshot()).isEqualTo(facts);
        assertThat(count("user_sessions", "status='ACTIVE'")).isZero();
        assertThat(count("user_sessions", "revoke_reason='RESTORE_EPOCH_ROTATED'")).isEqualTo(1);
        for (String table : List.of("spring_session", "spring_session_attributes", "oauth2_authorization", "oauth2_authorization_security",
                "oidc_password_challenges", "rate_limit_buckets", "captcha_challenges", "recovery_sessions", "registration_idempotency",
                "operation_idempotency")) assertThat(count(table, "true")).as(table).isZero();
        assertThat(count("oauth2_refresh_token_history", "status='REVOKED' AND revoked_at IS NOT NULL")).isEqualTo(2);
        assertThat(count("oauth2_authorization_consent", "true")).isEqualTo(1);
        assertThat(count("outbox_events", "true")).isEqualTo(1);
        assertThat(count("outbox_events", "claim_token IS NULL AND claimed_at IS NULL")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT details_json->>'oldEpoch' FROM security_events WHERE event_type='SECURITY_EPOCH_ROTATED_AFTER_RESTORE'")
                .query(String.class).single()).isEqualTo("fixture-epoch");
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring_session", "oauth2_authorization", "oidc_password_challenges", "operation_idempotency", "outbox_events", "security_events"})
    void aRealLateSqlFailureRollsBackEpochRevocationAndEveryClear(String table) {
        String before = wholeSnapshot();
        String operation = table.equals("security_events") ? "INSERT" : table.equals("outbox_events") ? "UPDATE" : "DELETE";
        jdbc.sql("CREATE FUNCTION fail_restore_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'restore-audit-fixture'; END $$").update();
        jdbc.sql("CREATE TRIGGER restore_failure BEFORE " + operation + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION fail_restore_fixture()").update();
        try {
            assertThatThrownBy(() -> recovery.rotateAfterRestore("fixture-epoch", "ROLLBACK_FIXTURE")).isInstanceOf(RuntimeException.class);
            assertThat(wholeSnapshot()).isEqualTo(before);
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS restore_failure ON " + table).update();
            jdbc.sql("DROP FUNCTION IF EXISTS fail_restore_fixture()").update();
        }
    }

    @Test void releasesOnlyPendingOutboxClaimsWithoutChangingPayloadAttemptsSchedulesOrTerminalReceipts() {
        jdbc.sql("UPDATE outbox_events SET claim_token=:claim, claimed_at=CURRENT_TIMESTAMP, attempt_count=3, last_error_code='HTTP_503'")
                .param("claim", UUID.randomUUID()).update();
        jdbc.sql("""
                INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload_json,created_at,available_at,
                    processed_at,attempt_count,last_error_code)
                VALUES (:processed,'USER',:user,'GLOBAL_LOGOUT_REQUESTED','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,1,NULL)
                """).param("processed", UUID.randomUUID()).param("user", user.toString()).update();
        jdbc.sql("""
                INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload_json,created_at,available_at,
                    dead_lettered_at,attempt_count,last_error_code)
                VALUES (:dead,'USER',:user,'BACKCHANNEL_LOGOUT_REQUESTED','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,8,'HTTP_503')
                """).param("dead", UUID.randomUUID()).param("user", user.toString()).update();
        var before = jdbc.sql("SELECT (to_jsonb(t)-'claim_token'-'claimed_at')::text FROM outbox_events t ORDER BY id")
                .query(String.class).list();
        recovery.rotateAfterRestore("fixture-epoch", "OUTBOX_RECOVERY_FIXTURE");
        assertThat(jdbc.sql("SELECT (to_jsonb(t)-'claim_token'-'claimed_at')::text FROM outbox_events t ORDER BY id")
                .query(String.class).list()).isEqualTo(before);
        assertThat(count("outbox_events", "processed_at IS NULL AND dead_lettered_at IS NULL AND claim_token IS NULL AND claimed_at IS NULL"))
                .isEqualTo(1);
        assertThat(count("outbox_events", "processed_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("outbox_events", "dead_lettered_at IS NOT NULL")).isEqualTo(1);
    }

    @Test void rejectsWrongOrRepeatedExpectedEpochBeforeMutation() {
        String before = wholeSnapshot();
        assertThatThrownBy(() -> recovery.rotateAfterRestore("other-epoch", "TEST")).hasMessage("RESTORED_EPOCH_MISMATCH");
        assertThat(wholeSnapshot()).isEqualTo(before);
        var rotated = recovery.rotateAfterRestore("fixture-epoch", "TEST");
        assertThatThrownBy(() -> recovery.rotateAfterRestore("fixture-epoch", "TEST")).hasMessage("RESTORED_EPOCH_MISMATCH");
        assertThat(epoch()).isEqualTo(rotated.newEpoch());
        assertThat(count("security_events", "event_type='SECURITY_EPOCH_ROTATED_AFTER_RESTORE'")).isEqualTo(1);
    }

    @Test void missingEpochFailsClosedWithoutInventingState() {
        jdbc.sql("DELETE FROM system_security_state WHERE id=1").update();
        try {
            assertThatThrownBy(() -> recovery.rotateAfterRestore("fixture-epoch", "TEST")).hasMessage("SECURITY_EPOCH_UNAVAILABLE");
            assertThat(count("spring_session", "true")).isEqualTo(1);
        } finally {
            jdbc.sql("INSERT INTO system_security_state VALUES (1,'fixture-epoch',CURRENT_TIMESTAMP,'TEST')").update();
        }
    }

    @Test void invalidReasonOrExpectedEpochDoesNotModifyState() {
        String before = wholeSnapshot();
        for (String reason : new String[] {null, " ", "x".repeat(129)}) {
            assertThatThrownBy(() -> recovery.rotateAfterRestore("fixture-epoch", reason)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String expected : new String[] {null, " ", "x".repeat(65)}) {
            assertThatThrownBy(() -> recovery.rotateAfterRestore(expected, "TEST")).hasMessage("EXPECTED_RESTORED_EPOCH_REQUIRED");
        }
        assertThat(wholeSnapshot()).isEqualTo(before);
    }

    @Test void concurrentOperatorsUsingSameExpectedEpochProduceOnlyOneCommittedReset() throws Exception {
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try { recovery.rotateAfterRestore("fixture-epoch", "TEST"); return true; }
                catch (SecurityEpochRecoveryException mismatch) {
                    assertThat(mismatch.getMessage()).isEqualTo("RESTORED_EPOCH_MISMATCH");
                    return false;
                }
            })).toList();
            int successes = 0;
            for (var call : calls) if (call.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(count("security_events", "event_type='SECURITY_EPOCH_ROTATED_AFTER_RESTORE'")).isEqualTo(1);
        }
    }

    private String epoch() { return jdbc.sql("SELECT security_epoch FROM system_security_state WHERE id=1").query(String.class).single(); }
    private long count(String table, String condition) { return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + condition).query(Long.class).single(); }
    private String permanentSnapshot() {
        var rows = new StringBuilder();
        for (String table : List.of("users", "usernames", "password_credentials", "recovery_codes", "admin_recovery_credentials",
                "account_deletion_requests", "oauth2_registered_client", "oauth2_authorization_consent", "outbox_events", "admin_audit_events")) {
            rows.append(table).append(jdbc.sql("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY row_to_json(t)::text")
                    .query(String.class).list());
        }
        return rows.toString(); // test snapshot only; never printed
    }
    private String wholeSnapshot() {
        var rows = new StringBuilder(permanentSnapshot());
        for (String table : List.of("system_security_state", "user_sessions", "spring_session", "spring_session_attributes",
                "oauth2_authorization", "oauth2_authorization_security", "oauth2_refresh_token_history", "oidc_password_challenges",
                "rate_limit_buckets", "captcha_challenges", "recovery_sessions", "registration_idempotency", "operation_idempotency", "security_events")) {
            rows.append(table).append(jdbc.sql("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY row_to_json(t)::text")
                    .query(String.class).list());
        }
        return rows.toString();
    }
}
