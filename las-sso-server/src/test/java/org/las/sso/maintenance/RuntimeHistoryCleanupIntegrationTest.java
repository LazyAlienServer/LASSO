package org.las.sso.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.config.HistoryRetentionProperties;
import org.las.sso.identity.JdbcLasUserDetailsService;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.security.PostgresTransientStateCleanup;
import org.las.sso.session.UserSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RuntimeHistoryCleanupIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AccountRegistrationService registrations;
    @Autowired UserSessionService sessions;
    @Autowired JdbcLasUserDetailsService principals;
    @Autowired PostgresTransientStateCleanup transientCleanup;
    SimpleMeterRegistry metrics;
    RuntimeHistoryCleanup cleanup;
    UUID user;

    @BeforeEach
    void setup() {
        jdbc.sql(
                        "TRUNCATE users, oauth2_registered_client, user_sessions, oauth2_refresh_token_history, admin_audit_events, security_events, outbox_events, oidc_password_challenges CASCADE")
                .update();
        metrics = new SimpleMeterRegistry();
        cleanup =
                new RuntimeHistoryCleanup(
                        jdbc,
                        new HistoryRetentionProperties(
                                2,
                                Duration.ofDays(90),
                                Duration.ofDays(90),
                                Duration.ofDays(30),
                                Duration.ofDays(30),
                                Duration.ofDays(30),
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(5)),
                        manager,
                        metrics);
        user =
                registrations
                        .register("CleanupUser", "Cleanup User", "unique cleanup fixture phrase")
                        .userId();
        jdbc.sql(
                        """
                INSERT INTO oauth2_registered_client(id,client_id,client_name,client_authentication_methods,authorization_grant_types,scopes,client_settings,token_settings)
                VALUES ('cleanup-client','cleanup-client','Cleanup','client_secret_basic','authorization_code,refresh_token','openid','{}','{}')
                """)
                .update();
    }

    @Test
    void authorizationReclamationRevokesHistoryAndCascadesHintsButKeepsLiveAssociations() {
        authorization("expired", true);
        authorization("live", false);
        history("expired", "CURRENT", true);
        history("live", "ROTATED", false);
        hint("expired");
        hint("live");
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isOne();
        assertThat(count("oauth2_authorization")).isOne();
        assertThat(
                        jdbc.sql("SELECT authorization_id FROM oauth2_id_token_hint_history")
                                .query(String.class)
                                .single())
                .isEqualTo("live");
        assertThat(
                        jdbc.sql(
                                        "SELECT status::text FROM oauth2_refresh_token_history WHERE authorization_id='expired'")
                                .query(String.class)
                                .single())
                .isEqualTo("REVOKED");
        // The trigger just revoked this row; Token expiry alone cannot erase its new receipt.
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.REFRESH_HISTORY)).isZero();
        jdbc.sql(
                        "UPDATE oauth2_refresh_token_history SET revoked_at=CURRENT_TIMESTAMP - INTERVAL '31 days' WHERE authorization_id='expired'")
                .update();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.REFRESH_HISTORY)).isOne();
        assertThat(
                        jdbc.sql("SELECT authorization_id FROM oauth2_refresh_token_history")
                                .query(String.class)
                                .single())
                .isEqualTo("live");
    }

    @Test
    void safeExpiryRequiresAllTokenAndFamilyDeadlinesAndUnknownStateIsProtected() {
        authorization("family-live", true);
        authorization("token-live", true);
        authorization("unknown", true);
        authorization("empty", true);
        jdbc.sql(
                        "UPDATE oauth2_authorization_security SET family_absolute_expires_at=CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE authorization_id='family-live'")
                .update();
        jdbc.sql(
                        "UPDATE oauth2_authorization SET access_token_value='fixture',access_token_expires_at=CURRENT_TIMESTAMP + INTERVAL '1 minute' WHERE id='token-live'")
                .update();
        jdbc.sql(
                        "UPDATE oauth2_authorization SET refresh_token_value='fixture',refresh_token_expires_at=NULL WHERE id='unknown'")
                .update();
        jdbc.sql(
                        "UPDATE oauth2_authorization SET authorization_code_expires_at=NULL,access_token_expires_at=NULL,oidc_id_token_expires_at=NULL,refresh_token_expires_at=NULL WHERE id='empty'")
                .update();
        jdbc.sql(
                        "UPDATE oauth2_authorization_security SET token_family_id=NULL,family_created_at=NULL,family_absolute_expires_at=NULL WHERE authorization_id='empty'")
                .update();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isZero();
        assertThat(count("oauth2_authorization")).isEqualTo(4);
    }

    @Test
    void currentStoredHistoryDeadlineIsAConservativeGuardAgainstInconsistentParentDates() {
        authorization("parent-expired", true);
        history("parent-expired", "CURRENT", false);
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isZero();
        assertThat(count("oauth2_authorization")).isOne();
    }

    @Test
    void statementTimeoutRollsBackPartialWorkAndCanRetryAfterStorageRepair() {
        authorization("slow", true);
        hint("slow");
        jdbc.sql(
                        "CREATE FUNCTION cleanup_delay_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(6); RETURN OLD; END $$")
                .update();
        jdbc.sql(
                        "CREATE TRIGGER cleanup_delay BEFORE DELETE ON oauth2_authorization FOR EACH ROW EXECUTE FUNCTION cleanup_delay_fixture()")
                .update();
        try {
            assertThatThrownBy(() -> cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count("oauth2_authorization")).isOne();
            assertThat(count("oauth2_id_token_hint_history")).isOne();
            assertThat(
                            metrics.get("las.sso.history.cleanup.rows")
                                    .tag("phase", "authorizations")
                                    .counter()
                                    .count())
                    .isZero();
        } finally {
            jdbc.sql("DROP TRIGGER cleanup_delay ON oauth2_authorization").update();
            jdbc.sql("DROP FUNCTION cleanup_delay_fixture()").update();
        }
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isOne();
    }

    @Test
    void advisoryLockSkipsASecondExecutorWithoutWaiting() throws Exception {
        outbox("success", true);
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            connection.createStatement().execute("SELECT pg_advisory_xact_lock(4773916220242)");
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertThat(
                                executor.submit(
                                                () ->
                                                        cleanup.cleanup(
                                                                RuntimeHistoryCleanup.Phase.OUTBOX))
                                        .get(3, TimeUnit.SECONDS))
                        .isZero();
            }
            connection.rollback();
        }
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.OUTBOX)).isOne();
    }

    @Test
    void candidateLookaheadSkipsOversizedFamiliesWithoutStarvingOtherExpiredAuthorizations() {
        authorization("large-first", true);
        authorization("small-later", true);
        jdbc.sql(
                        "UPDATE oauth2_authorization SET refresh_token_expires_at=CURRENT_TIMESTAMP-INTERVAL '50 days' WHERE id='large-first'")
                .update();
        jdbc.sql(
                        """
                INSERT INTO oauth2_refresh_token_history(token_hash,token_family_id,authorization_id,status,issued_at,expires_at,rotated_at)
                SELECT sha256(convert_to('large'||n,'UTF8')),md5('large')::uuid,'large-first','ROTATED',CURRENT_TIMESTAMP-INTERVAL '60 days',CURRENT_TIMESTAMP-INTERVAL '40 days',CURRENT_TIMESTAMP-INTERVAL '41 days' FROM generate_series(1,10001)n
                """)
                .update();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isOne();
        assertThat(jdbc.sql("SELECT id FROM oauth2_authorization").query(String.class).single())
                .isEqualTo("large-first");
    }

    @Test
    void finiteBatchesDrainExpiredRowsAndRepeatedCleanupIsIdempotent() {
        for (int n = 0; n < 5; n++) authorization("expired-" + n, true);
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isEqualTo(2);
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isEqualTo(2);
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isOne();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isZero();
        assertThat(
                        metrics.get("las.sso.history.cleanup.rows")
                                .tag("phase", "authorizations")
                                .counter()
                                .count())
                .isEqualTo(5);
    }

    @Test
    void expiryMarksUnvisitedSessionsAndPurgedOldSidCannotRegisterAsNewLogin() {
        var principal = (LasUserPrincipal) principals.loadUserByUsername("CleanupUser");
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        String sid = sessions.registerLogin(principal, request);
        jdbc.sql(
                        "UPDATE user_sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '200 days',last_seen_at=CURRENT_TIMESTAMP-INTERVAL '200 days',idle_expires_at=CURRENT_TIMESTAMP-INTERVAL '100 days',absolute_expires_at=CURRENT_TIMESTAMP-INTERVAL '90 days' WHERE sid=:sid")
                .param("sid", sid)
                .update();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.SESSION_EXPIRY)).isOne();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.SESSIONS)).isOne();
        assertThat(sessions.validateOrRegister(principal, request)).isFalse();
        assertThat(count("user_sessions")).isZero();
        assertThat(count("users")).isOne();
    }

    @Test
    void sessionsReferencedBySurvivingAuthorizationAreNotPurged() {
        session("referenced", true);
        session("recent", false);
        authorization("live", false);
        jdbc.sql(
                        "UPDATE oauth2_authorization_security SET sid='referenced' WHERE authorization_id='live'")
                .update();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.SESSIONS)).isZero();
        assertThat(count("user_sessions")).isEqualTo(2);
    }

    @Test
    void credentialCleanupKeepsUnusedCodesAndRequiresOldUseRevocationAndManualExpiry() {
        jdbc.sql(
                        "UPDATE recovery_codes SET used_at=CURRENT_TIMESTAMP-INTERVAL '100 days' WHERE user_id=:user")
                .param("user", user)
                .update();
        jdbc.sql(
                        "INSERT INTO recovery_codes(id,user_id,code_hash,generation,created_at) VALUES (:id,:user,decode(repeat('00',32),'hex'),2,CURRENT_TIMESTAMP-INTERVAL '200 days')")
                .param("id", UUID.randomUUID())
                .param("user", user)
                .update();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.RECOVERY_CODES)).isEqualTo(2);
        for (int i = 0; i < 4; i++) cleanup.cleanup(RuntimeHistoryCleanup.Phase.RECOVERY_CODES);
        assertThat(count("recovery_codes")).isOne();
        manual(true, true);
        manual(true, false);
        manual(false, true);
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.MANUAL_CREDENTIALS)).isOne();
        assertThat(count("admin_recovery_credentials")).isEqualTo(2);
    }

    @Test
    void successfulOutboxOnlyAndAuditDeletionFactsStayIntact() {
        outbox("success", true);
        outbox("dead", false);
        outbox("pending", false);
        outbox("claimed", true);
        outbox("unknown", true);
        jdbc.sql(
                        "UPDATE outbox_events SET dead_lettered_at=CURRENT_TIMESTAMP WHERE aggregate_id='dead'")
                .update();
        jdbc.sql(
                        "UPDATE outbox_events SET claim_token=:claim,claimed_at=CURRENT_TIMESTAMP WHERE aggregate_id='claimed'")
                .param("claim", UUID.randomUUID())
                .update();
        jdbc.sql("UPDATE outbox_events SET event_type='UNKNOWN_EVENT' WHERE aggregate_id='unknown'")
                .update();
        jdbc.sql(
                        "INSERT INTO admin_audit_events(id,occurred_at,action,target_type,target_id,request_id,result) VALUES(:id,CURRENT_TIMESTAMP-INTERVAL '2 years','FIXTURE','USER',:target,:request,'SUCCESS')")
                .param("id", UUID.randomUUID())
                .param("target", user.toString())
                .param("request", UUID.randomUUID())
                .update();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.OUTBOX)).isOne();
        assertThat(count("outbox_events")).isEqualTo(4);
        assertThat(count("admin_audit_events")).isOne();
        assertThat(count("users")).isOne();
        assertThat(count("usernames")).isOne();
    }

    @Test
    void rollbackDoesNotDeleteChildrenOrPublishSuccessfulCleanupMetrics() {
        authorization("fault", true);
        history("fault", "CURRENT", true);
        hint("fault");
        jdbc.sql(
                        "CREATE FUNCTION cleanup_reject_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture cleanup failure'; END $$")
                .update();
        jdbc.sql(
                        "CREATE TRIGGER cleanup_fault BEFORE DELETE ON oauth2_authorization FOR EACH ROW EXECUTE FUNCTION cleanup_reject_fixture()")
                .update();
        try {
            assertThatThrownBy(() -> cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("oauth2_authorization")).isOne();
            assertThat(count("oauth2_id_token_hint_history")).isOne();
            assertThat(
                            jdbc.sql("SELECT status::text FROM oauth2_refresh_token_history")
                                    .query(String.class)
                                    .single())
                    .isEqualTo("CURRENT");
            assertThat(
                            metrics.get("las.sso.history.cleanup.rows")
                                    .tag("phase", "authorizations")
                                    .counter()
                                    .count())
                    .isZero();
        } finally {
            jdbc.sql("DROP TRIGGER cleanup_fault ON oauth2_authorization").update();
            jdbc.sql("DROP FUNCTION cleanup_reject_fixture()").update();
        }
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isOne();
    }

    @Test
    void heldRefreshHistorySkipsAuthorizationWithoutKeepingPartialLocks() throws Exception {
        authorization("busy", true);
        history("busy", "CURRENT", true);
        authorization("other", true);
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            connection
                    .createStatement()
                    .execute(
                            "SELECT token_hash FROM oauth2_refresh_token_history WHERE authorization_id='busy' FOR UPDATE");
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertThat(
                                executor.submit(
                                                () ->
                                                        cleanup.cleanup(
                                                                RuntimeHistoryCleanup.Phase
                                                                        .AUTHORIZATIONS))
                                        .get(3, TimeUnit.SECONDS))
                        .isOne();
            }
            assertThat(jdbc.sql("SELECT id FROM oauth2_authorization").query(String.class).single())
                    .isEqualTo("busy");
            connection.rollback();
        }
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isOne();
    }

    @Test
    void concurrentExpiryRenewalProtectsSessionAndOtherExpiredRowsCanProgress() throws Exception {
        session("busy", false);
        session("other", false);
        jdbc.sql(
                        "UPDATE user_sessions SET status='ACTIVE',revoked_at=NULL,idle_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute'")
                .update();
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            connection
                    .createStatement()
                    .execute(
                            "UPDATE user_sessions SET idle_expires_at=CURRENT_TIMESTAMP+INTERVAL '1 hour' WHERE sid='busy'");
            assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.SESSION_EXPIRY)).isOne();
            connection.commit();
        }
        assertThat(
                        jdbc.sql("SELECT status::text FROM user_sessions WHERE sid='busy'")
                                .query(String.class)
                                .single())
                .isEqualTo("ACTIVE");
    }

    @Test
    void transientCleanupSkipsLockedExpiredChallengeAndKeepsUnexpiredState() throws Exception {
        UUID busy = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO oidc_password_challenges(id,expires_at) VALUES (:busy,CURRENT_TIMESTAMP-INTERVAL '1 minute'),(:other,CURRENT_TIMESTAMP-INTERVAL '1 minute'),(:live,CURRENT_TIMESTAMP+INTERVAL '5 minutes')")
                .param("busy", busy)
                .param("other", other)
                .param("live", live)
                .update();
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            connection
                    .createStatement()
                    .execute(
                            "SELECT id FROM oidc_password_challenges WHERE id='"
                                    + busy
                                    + "' FOR UPDATE");
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertThat(
                                executor.submit(() -> transientCleanup.cleanupExpired(10))
                                        .get(3, TimeUnit.SECONDS))
                        .isOne();
            }
            connection.rollback();
        }
        assertThat(count("oidc_password_challenges")).isEqualTo(2);
        assertThat(transientCleanup.cleanupExpired(10)).isOne();
        assertThat(jdbc.sql("SELECT id FROM oidc_password_challenges").query(UUID.class).single())
                .isEqualTo(live);
    }

    @Test
    void oversizedRefreshHistoryDoesNotAllocateUnboundedOrDeleteLiveProtection() {
        authorization("large", true);
        jdbc.sql(
                        """
                INSERT INTO oauth2_refresh_token_history(token_hash,token_family_id,authorization_id,status,issued_at,expires_at,rotated_at)
                SELECT sha256(convert_to('large'||n,'UTF8')),md5('large')::uuid,'large','ROTATED',CURRENT_TIMESTAMP-INTERVAL '50 days',CURRENT_TIMESTAMP-INTERVAL '40 days',CURRENT_TIMESTAMP-INTERVAL '41 days' FROM generate_series(1,10001)n
                """)
                .update();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isZero();
        assertThat(count("oauth2_authorization")).isOne();
        assertThat(
                        metrics.get("las.sso.history.cleanup.oversized.authorizations")
                                .counter()
                                .count())
                .isOne();
    }

    @Test
    void busyAuthorizationDoesNotHoldHistoryLocksAndRenewalIsRechecked() throws Exception {
        authorization("busy-parent", true);
        history("busy-parent", "CURRENT", true);
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            connection
                    .createStatement()
                    .execute(
                            "UPDATE oauth2_authorization SET access_token_expires_at=CURRENT_TIMESTAMP+INTERVAL '1 day' WHERE id='busy-parent'");
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertThat(
                                executor.submit(
                                                () ->
                                                        cleanup.cleanup(
                                                                RuntimeHistoryCleanup.Phase
                                                                        .AUTHORIZATIONS))
                                        .get(3, TimeUnit.SECONDS))
                        .isZero();
            }
            // Skipped parent releases partial child locks, so this writer can proceed immediately.
            connection.createStatement().execute("SET LOCAL lock_timeout = '500ms'");
            connection
                    .createStatement()
                    .execute(
                            "SELECT token_hash FROM oauth2_refresh_token_history WHERE authorization_id='busy-parent' FOR UPDATE");
            connection.commit();
        }
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS)).isZero();
        assertThat(count("oauth2_authorization")).isOne();
    }

    @Test
    void phaseFailureDoesNotStarveUnrelatedScheduledOutboxCleanup() {
        outbox("success", true);
        jdbc.sql("ALTER TABLE user_sessions RENAME TO fixture_missing_sessions").update();
        try {
            cleanup.scheduledCleanup();
        } finally {
            jdbc.sql("ALTER TABLE fixture_missing_sessions RENAME TO user_sessions").update();
        }
        assertThat(count("outbox_events")).isZero();
        assertThat(
                        metrics.get("las.sso.history.cleanup.failures")
                                .tag("phase", "session_expiry")
                                .counter()
                                .count())
                .isOne();
    }

    @Test
    void orphanRefreshHashesNeedRevokedStatusAndBothSafetyDeadlines() {
        authorization("source", true);
        history("source", "CURRENT", true);
        cleanup.cleanup(RuntimeHistoryCleanup.Phase.AUTHORIZATIONS);
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.REFRESH_HISTORY)).isZero();
        jdbc.sql(
                        "UPDATE oauth2_refresh_token_history SET revoked_at=CURRENT_TIMESTAMP-INTERVAL '40 days',expires_at=CURRENT_TIMESTAMP+INTERVAL '1 day'")
                .update();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.REFRESH_HISTORY)).isZero();
        jdbc.sql(
                        "UPDATE oauth2_refresh_token_history SET expires_at=CURRENT_TIMESTAMP-INTERVAL '40 days'")
                .update();
        assertThat(cleanup.cleanup(RuntimeHistoryCleanup.Phase.REFRESH_HISTORY)).isOne();
    }

    private void authorization(String id, boolean expired) {
        String expiry =
                expired
                        ? "CURRENT_TIMESTAMP-INTERVAL '40 days'"
                        : "CURRENT_TIMESTAMP+INTERVAL '1 day'";
        jdbc.sql(
                        "INSERT INTO oauth2_authorization(id,registered_client_id,principal_name,authorization_grant_type,refresh_token_expires_at) VALUES(:id,'cleanup-client',:user,'authorization_code',"
                                + expiry
                                + ")")
                .param("id", id)
                .param("user", user.toString())
                .update();
        jdbc.sql(
                        "INSERT INTO oauth2_authorization_security(authorization_id,credentials_version,security_epoch,client_config_version,token_family_id,family_created_at,family_absolute_expires_at) VALUES(:id,1,'fixture',1,:family,CURRENT_TIMESTAMP-INTERVAL '60 days',"
                                + expiry
                                + ")")
                .param("id", id)
                .param("family", UUID.randomUUID())
                .update();
    }

    private void history(String id, String status, boolean expired) {
        jdbc.sql(
                        "INSERT INTO oauth2_refresh_token_history(token_hash,token_family_id,authorization_id,status,issued_at,expires_at,rotated_at) VALUES(:hash,:family,:id,CAST(:status AS refresh_token_status),CURRENT_TIMESTAMP-INTERVAL '60 days',"
                                + (expired
                                        ? "CURRENT_TIMESTAMP-INTERVAL '40 days'"
                                        : "CURRENT_TIMESTAMP+INTERVAL '1 day'")
                                + ",CASE WHEN :status='ROTATED' THEN CURRENT_TIMESTAMP-INTERVAL '41 days' END)")
                .param("hash", id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .param("family", UUID.randomUUID())
                .param("id", id)
                .param("status", status)
                .update();
    }

    private void hint(String id) {
        jdbc.sql(
                        "INSERT INTO oauth2_id_token_hint_history(token_hash,authorization_id,created_at) VALUES(:hash,:id,CURRENT_TIMESTAMP-INTERVAL '40 days')")
                .param("hash", id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .param("id", id)
                .update();
    }

    private void session(String sid, boolean old) {
        jdbc.sql(
                        "INSERT INTO user_sessions(id,sid,user_id,status,credentials_version,security_epoch,created_at,last_seen_at,idle_expires_at,absolute_expires_at,last_password_auth_at,revoked_at) VALUES(:id,:sid,:user,'REVOKED',1,'fixture',CURRENT_TIMESTAMP-INTERVAL '200 days',CURRENT_TIMESTAMP-INTERVAL '200 days',CURRENT_TIMESTAMP-INTERVAL '100 days',CURRENT_TIMESTAMP+INTERVAL '1 day',CURRENT_TIMESTAMP-INTERVAL '200 days',"
                                + (old
                                        ? "CURRENT_TIMESTAMP-INTERVAL '100 days'"
                                        : "CURRENT_TIMESTAMP-INTERVAL '1 day'")
                                + ")")
                .param("id", UUID.randomUUID())
                .param("sid", sid)
                .param("user", user)
                .update();
    }

    private void manual(boolean revoked, boolean expired) {
        jdbc.sql(
                        "INSERT INTO admin_recovery_credentials(id,target_user_id,issued_by_user_id,credential_hash,expires_at,revoked_at,reason,created_at) VALUES(:id,:user,:user,decode(repeat('00',32),'hex'),"
                                + (expired
                                        ? "CURRENT_TIMESTAMP-INTERVAL '100 days'"
                                        : "CURRENT_TIMESTAMP+INTERVAL '1 day'")
                                + ","
                                + (revoked ? "CURRENT_TIMESTAMP-INTERVAL '100 days'" : "NULL")
                                + ",'fixture',CURRENT_TIMESTAMP-INTERVAL '200 days')")
                .param("id", UUID.randomUUID())
                .param("user", user)
                .update();
    }

    private void outbox(String name, boolean done) {
        jdbc.sql(
                        "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload_json,created_at,available_at,processed_at) VALUES(:id,'USER',:name,'GLOBAL_LOGOUT_REQUESTED','{}',CURRENT_TIMESTAMP-INTERVAL '50 days',CURRENT_TIMESTAMP-INTERVAL '50 days',"
                                + (done ? "CURRENT_TIMESTAMP-INTERVAL '40 days'" : "NULL")
                                + ")")
                .param("id", UUID.randomUUID())
                .param("name", name)
                .update();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
