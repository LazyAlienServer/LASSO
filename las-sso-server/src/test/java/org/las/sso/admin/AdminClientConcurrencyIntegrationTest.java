package org.las.sso.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.account.OperationIdempotencyService;
import org.las.sso.account.IdempotencyConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** Spring-proxied client writers/actual PostgreSQL waits; OIDC authorizations and actor identity are explicit fixtures. */
@SpringBootTest(properties = {"las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class AdminClientConcurrencyIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired AdminClientService clients;
    @Autowired RegisteredClientRepository registered;
    @Autowired AccountRegistrationService accounts;
    @Autowired OAuth2AuthorizationService authorizations;
    @Autowired OAuth2AuthorizationConsentService consents;
    @Autowired OperationIdempotencyService idempotency;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @ParameterizedTest
    @CsvSource({"NAME,ROTATE", "ROTATE,NAME", "SHRINK,ROTATE", "ROTATE,SHRINK",
            "DISABLE,ROTATE", "ROTATE,DISABLE", "ENABLE,ROTATE", "ROTATE,ENABLE", "ROTATE,ROTATE"})
    void committedWritersSerializeAndNeverRestoreAnEarlierSecret(Mutation first, Mutation second) throws Exception {
        var fixture = fixture();
        long startVersion = clients.detail(fixture.clientId()).configVersion();
        var firstSecret = new AtomicReference<String>();
        var follower = new AtomicReference<Future<String>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                firstSecret.set(apply(first, fixture));
                int owner = pid();
                var followerPid = new AtomicInteger();
                var entered = new CountDownLatch(1);
                follower.set(executor.submit(() -> new TransactionTemplate(transactions).execute(other -> {
                    followerPid.set(pid());
                    entered.countDown();
                    return apply(second, fixture);
                })));
                awaitBlocked(owner, followerPid, entered);
            });
            String secondSecret = follower.get().get(10, TimeUnit.SECONDS);
            String finalSecret = second == Mutation.ROTATE ? secondSecret : first == Mutation.ROTATE ? firstSecret.get() : fixture.secret();
            assertThat(encoder.matches(finalSecret, registered.findByClientId(fixture.clientId()).getClientSecret())).isTrue();
            if (first == Mutation.ROTATE || second == Mutation.ROTATE) {
                assertThat(encoder.matches(fixture.secret(), registered.findByClientId(fixture.clientId()).getClientSecret())).isFalse();
                if (first == Mutation.ROTATE && second == Mutation.ROTATE) {
                    assertThat(encoder.matches(firstSecret.get(), registered.findByClientId(fixture.clientId()).getClientSecret())).isFalse();
                }
            }
            assertThat(clients.detail(fixture.clientId()).configVersion())
                    .isEqualTo(startVersion + (first == Mutation.NAME ? 0 : 1) + (second == Mutation.NAME ? 0 : 1));
            assertThat(clients.detail(fixture.clientId()).status())
                    .isEqualTo(first == Mutation.DISABLE || second == Mutation.DISABLE ? "DISABLED" : "ACTIVE");
            assertThat(authorizationCount(fixture)).isZero();
            assertThat(consentCount(fixture)).isZero();
            assertThat(outboxCount(fixture)).isOne();
            assertThat(auditCount(fixture, first.action())).isEqualTo(first == second ? 2 : 1);
            assertThat(auditCount(fixture, second.action())).isEqualTo(first == second ? 2 : 1);
            if (first == Mutation.NAME || second == Mutation.NAME) assertThat(clients.detail(fixture.clientId()).clientName()).isEqualTo("Edited fixture");
            if (first == Mutation.SHRINK || second == Mutation.SHRINK) assertThat(clients.detail(fixture.clientId()).scopes()).containsExactly("openid");
            query(fixture, finalSecret).andExpect(first == Mutation.DISABLE || second == Mutation.DISABLE
                    ? status().isUnauthorized() : status().isOk());
        }
    }

    @Test
    void committedRotationChangesActualBasicAuthenticationAndInvalidatesOldSecret() throws Exception {
        var fixture = fixture();
        query(fixture, fixture.secret()).andExpect(status().isOk());
        var rotated = clients.rotateSecret(fixture.clientId(), fixture.actorId(), null, "verified rotation", UUID.randomUUID());

        query(fixture, fixture.secret()).andExpect(status().isUnauthorized());
        query(fixture, rotated.clientSecret()).andExpect(status().isOk());
        assertThat(authorizationCount(fixture)).isZero();
        assertThat(consentCount(fixture)).isZero();
        assertThat(outboxCount(fixture)).isOne();
    }

    @Test
    void rotationChangesOAuthEndpointClientAuthenticationWithoutInventingAnAuthorizationGrant() throws Exception {
        var fixture = fixture();
        var rotated = clients.rotateSecret(fixture.clientId(), fixture.actorId(), null, "oauth authentication check", UUID.randomUUID());

        var oldResponse = mvc.perform(post("/oauth2/token").with(httpBasic(fixture.clientId(), fixture.secret()))
                        .param("grant_type", "authorization_code").param("code", "nonexistent-s2-code")
                        .param("redirect_uri", "https://client.example/callback"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(json.readTree(oldResponse.getContentAsString()).get("error").asText()).isEqualTo("invalid_client");
        var newResponse = mvc.perform(post("/oauth2/token").with(httpBasic(fixture.clientId(), rotated.clientSecret()))
                        .param("grant_type", "authorization_code").param("code", "nonexistent-s2-code")
                        .param("redirect_uri", "https://client.example/callback"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(json.readTree(newResponse.getContentAsString()).get("error").asText()).isEqualTo("invalid_grant");
    }

    @Test
    void disableThenRotateThenEnableDoesNotRestoreOldSecretOrAuthorizations() throws Exception {
        var fixture = fixture();
        clients.disable(fixture.clientId(), fixture.actorId(), null, "maintenance", UUID.randomUUID());
        var rotated = clients.rotateSecret(fixture.clientId(), fixture.actorId(), null, "maintenance rotation", UUID.randomUUID());
        query(fixture, rotated.clientSecret()).andExpect(status().isUnauthorized());

        clients.enable(fixture.clientId(), fixture.actorId(), null, "maintenance complete", UUID.randomUUID());

        assertThat(clients.detail(fixture.clientId()).configVersion()).isEqualTo(4);
        query(fixture, fixture.secret()).andExpect(status().isUnauthorized());
        query(fixture, rotated.clientSecret()).andExpect(status().isOk());
        assertThat(authorizationCount(fixture)).isZero();
        assertThat(consentCount(fixture)).isZero();
        assertThat(outboxCount(fixture)).isOne();
    }

    @Test
    void anotherClientCanRotateWhileTheFirstMetadataRowIsLocked() throws Exception {
        var locked = fixture();
        var independent = fixture();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.sql("SELECT registered_client_id FROM registered_client_metadata WHERE registered_client_id = :id FOR UPDATE")
                        .param("id", locked.registeredId()).query(String.class).single();
                try {
                    var rotated = executor.submit(() -> clients.rotateSecret(independent.clientId(), independent.actorId(), null,
                            "independent client", UUID.randomUUID())).get(5, TimeUnit.SECONDS);
                    assertThat(encoder.matches(rotated.clientSecret(), registered.findByClientId(independent.clientId()).getClientSecret())).isTrue();
                } catch (Exception exception) { throw new AssertionError("Independent client rotation must not wait", exception); }
            });
        }
        assertThat(clients.detail(locked.clientId()).configVersion()).isOne();
        assertThat(clients.detail(independent.clientId()).configVersion()).isEqualTo(2);
    }

    @Test
    void actualOutboxSqlFailureRollsBackRotatedSecretBeforeAuthorizationDeletion() {
        var fixture = fixture();
        var before = snapshot(fixture);
        String name = "s2_outbox_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.payload_json->>'clientId' = '"
                + fixture.clientId() + "' THEN RAISE EXCEPTION 's2_fixture_outbox_failure'; END IF; RETURN NEW; END; $$").update();
        try {
            jdbc.sql("CREATE TRIGGER " + name + " BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION " + name + "()").update();
            try { assertThatThrownBy(() -> apply(Mutation.ROTATE, fixture)).isInstanceOf(DataAccessException.class); }
            finally { jdbc.sql("DROP TRIGGER " + name + " ON outbox_events").update(); }
        } finally { jdbc.sql("DROP FUNCTION " + name + "()").update(); }
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    @Test
    void failedIdempotencyWriteRollsBackRotationAndRetryGeneratesOnlyOneCommittedResult() {
        var fixture = fixture();
        var before = snapshot(fixture);
        String key = UUID.randomUUID().toString();
        String name = "s2_idem_fault_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.idempotency_key = '"
                + key + "' THEN RAISE EXCEPTION 's2_fixture_idempotency_failure'; END IF; RETURN NEW; END; $$").update();
        try {
            jdbc.sql("CREATE TRIGGER " + name + " BEFORE INSERT ON operation_idempotency FOR EACH ROW EXECUTE FUNCTION " + name + "()").update();
            try { assertThatThrownBy(() -> idempotentRotate(fixture, key)).isInstanceOf(DataAccessException.class); }
            finally { jdbc.sql("DROP TRIGGER " + name + " ON operation_idempotency").update(); }
        } finally { jdbc.sql("DROP FUNCTION " + name + "()").update(); }
        assertThat(snapshot(fixture)).isEqualTo(before);
        assertThat(idempotentRotate(fixture, key).secretsAvailable()).isTrue();
        assertThat(idempotentRotate(fixture, key).clientSecret()).isNull();
        assertThat(auditCount(fixture, Mutation.ROTATE.action())).isOne();
    }

    @Test
    void idempotencyKeyWithChangedMaterialRejectsWithoutAnotherRotation() {
        var fixture = fixture();
        String key = UUID.randomUUID().toString();
        idempotentRotate(fixture, key);
        var before = snapshot(fixture);
        assertThatThrownBy(() -> idempotency.execute(fixture.actorId(), "ADMIN_CLIENT_SECRET_ROTATE", key,
                fixture.clientId() + "\u0000different reason", () -> apply(Mutation.ROTATE, fixture),
                ignored -> OperationIdempotencyService.StoredResult.empty(), stored -> "unexpected"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void actualAuditSqlFailureRollsBackClientSecurityAuthorizationAndOutbox(Mutation mutation) throws Exception {
        var fixture = fixture();
        var before = snapshot(fixture);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "s2_audit_fault_" + suffix;
        jdbc.sql("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.target_id = '" + fixture.clientId() + "' AND NEW.action = '" + mutation.action() + "' THEN "
                + "RAISE EXCEPTION 's2_fixture_audit_failure'; END IF; RETURN NEW; END; $$").update();
        try {
            jdbc.sql("CREATE TRIGGER " + function + " BEFORE INSERT ON admin_audit_events FOR EACH ROW EXECUTE FUNCTION " + function + "()").update();
            try {
                assertThatThrownBy(() -> apply(mutation, fixture)).isInstanceOf(DataAccessException.class);
            } finally { jdbc.sql("DROP TRIGGER " + function + " ON admin_audit_events").update(); }
        } finally { jdbc.sql("DROP FUNCTION " + function + "()").update(); }
        assertThat(snapshot(fixture)).isEqualTo(before);
        query(fixture, fixture.secret()).andExpect(status().isOk());
    }

    @Test
    void rollbackOfAnEarlierRotationLetsWaitingEditRetainOnlyTheCommittedOldSecret() throws Exception {
        var fixture = fixture();
        var secret = new AtomicReference<String>();
        var follower = new AtomicReference<Future<String>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                secret.set(apply(Mutation.ROTATE, fixture));
                int owner = pid();
                var followerPid = new AtomicInteger();
                var entered = new CountDownLatch(1);
                follower.set(executor.submit(() -> new TransactionTemplate(transactions).execute(other -> {
                    followerPid.set(pid()); entered.countDown(); return apply(Mutation.NAME, fixture);
                })));
                awaitBlocked(owner, followerPid, entered);
                tx.setRollbackOnly();
            });
            follower.get().get(10, TimeUnit.SECONDS);
        }
        assertThat(clients.detail(fixture.clientId()).clientName()).isEqualTo("Edited fixture");
        assertThat(clients.detail(fixture.clientId()).configVersion()).isOne();
        assertThat(encoder.matches(fixture.secret(), registered.findByClientId(fixture.clientId()).getClientSecret())).isTrue();
        assertThat(encoder.matches(secret.get(), registered.findByClientId(fixture.clientId()).getClientSecret())).isFalse();
        assertThat(authorizationCount(fixture)).isOne();
        assertThat(consentCount(fixture)).isOne();
        assertThat(outboxCount(fixture)).isZero();
        assertThat(auditCount(fixture, Mutation.ROTATE.action())).isZero();
    }

    @Test
    void concurrentIdempotentRotationReturnsOneSecretAndOneNonsecretReplay() throws Exception {
        var fixture = fixture();
        String key = UUID.randomUUID().toString();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        List<AdminClientService.RotationResult> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return idempotentRotate(fixture, key); });
            var second = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return idempotentRotate(fixture, key); });
            results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertThat(results.stream().filter(AdminClientService.RotationResult::secretsAvailable).count()).isOne();
        assertThat(results.stream().filter(AdminClientService.RotationResult::idempotentReplay).count()).isOne();
        var secret = results.stream().filter(AdminClientService.RotationResult::secretsAvailable).findFirst().orElseThrow().clientSecret();
        assertThat(results.stream().filter(AdminClientService.RotationResult::idempotentReplay).findFirst().orElseThrow().clientSecret()).isNull();
        assertThat(clients.detail(fixture.clientId()).configVersion()).isEqualTo(2);
        assertThat(auditCount(fixture, Mutation.ROTATE.action())).isOne();
        assertThat(outboxCount(fixture)).isOne();
        String record = jdbc.sql("SELECT to_jsonb(i)::text FROM operation_idempotency i WHERE actor_user_id = :actor AND idempotency_key = :key")
                .param("actor", fixture.actorId()).param("key", key).query(String.class).single();
        assertThat(record).doesNotContain(secret, fixture.secret());
        query(fixture, secret).andExpect(status().isOk());
    }

    private AdminClientService.RotationResult idempotentRotate(Fixture fixture, String key) {
        return idempotency.execute(fixture.actorId(), "ADMIN_CLIENT_SECRET_ROTATE", key, fixture.clientId() + "\u0000same reason",
                () -> clients.rotateSecret(fixture.clientId(), fixture.actorId(), null, "same reason", UUID.randomUUID()),
                result -> new OperationIdempotencyService.StoredResult(Long.toString(result.configVersion()), result.validFrom()),
                stored -> new AdminClientService.RotationResult(null, stored.resultTime(), null, Long.parseLong(stored.resultRef()), false, true));
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var user = accounts.register("S2User" + suffix, "Client fixture", "s2 account password phrase");
        var actor = accounts.register("S2Actor" + suffix, "Actor fixture", "s2 actor password phrase");
        var created = clients.create(new AdminClientService.CreateRequest("s2-client-" + suffix, "Original fixture", "FIRST_PARTY",
                List.of("https://client.example/callback"), List.of(), List.of("openid", "profile"), List.of("urn:las:s2"),
                "https://client.example/backchannel"), actor.userId(), null, "fixture create", UUID.randomUUID());
        var client = registered.findByClientId(created.clientId());
        var auth = OAuth2Authorization.withRegisteredClient(client).id(UUID.randomUUID().toString()).principalName(user.userId().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizedScopes(Set.of("openid", "profile")).build();
        authorizations.save(auth);
        jdbc.sql("UPDATE oauth2_authorization_security SET sid = :sid WHERE authorization_id = :id")
                .param("sid", "s2-sid-" + suffix).param("id", auth.getId()).update();
        consents.save(OAuth2AuthorizationConsent.withId(client.getId(), user.userId().toString()).scope("openid").scope("profile").build());
        return new Fixture(created.clientId(), client.getId(), created.clientSecret(), actor.userId(), user.userId(), auth.getId());
    }

    private String apply(Mutation mutation, Fixture f) {
        UUID request = UUID.randomUUID();
        switch (mutation) {
            case ROTATE -> { return clients.rotateSecret(f.clientId(), f.actorId(), null, "fixture rotate", request).clientSecret(); }
            case DISABLE -> clients.disable(f.clientId(), f.actorId(), null, "fixture disable", request);
            case ENABLE -> clients.enable(f.clientId(), f.actorId(), null, "fixture enable", request);
            case NAME, SHRINK -> {
                var current = clients.detail(f.clientId());
                clients.patch(f.clientId(), new AdminClientService.PatchRequest(mutation == Mutation.NAME ? "Edited fixture" : current.clientName(),
                        current.clientType(), current.redirectUris(), current.postLogoutRedirectUris(), mutation == Mutation.SHRINK ? List.of("openid") : current.scopes(),
                        current.resourceAudiences(), current.backchannelLogoutUri()), f.actorId(), null, "fixture edit", request);
            }
        }
        return f.secret();
    }

    private int pid() { return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single(); }

    private void awaitBlocked(int owner, AtomicInteger follower, CountDownLatch entered) {
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                if (jdbc.sql("SELECT :owner = ANY(pg_blocking_pids(:follower))").param("owner", owner).param("follower", follower.get())
                        .query(Boolean.class).single()) return;
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
            throw new AssertionError("Client writer did not wait on the owning PostgreSQL transaction");
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }

    private long authorizationCount(Fixture f) { return jdbc.sql("SELECT COUNT(*) FROM oauth2_authorization WHERE registered_client_id = :id").param("id", f.registeredId()).query(Long.class).single(); }
    private long consentCount(Fixture f) { return jdbc.sql("SELECT COUNT(*) FROM oauth2_authorization_consent WHERE registered_client_id = :id").param("id", f.registeredId()).query(Long.class).single(); }
    private long outboxCount(Fixture f) { return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE payload_json->>'clientId' = :id").param("id", f.clientId()).query(Long.class).single(); }
    private long auditCount(Fixture f, String action) { return jdbc.sql("SELECT COUNT(*) FROM admin_audit_events WHERE target_id = :id AND action = :action").param("id", f.clientId()).param("action", action).query(Long.class).single(); }

    private org.springframework.test.web.servlet.ResultActions query(Fixture f, String secret) throws Exception {
        return mvc.perform(get("/internal/v1/principals/{id}/service-access", f.userId()).with(httpBasic(f.clientId(), secret)));
    }

    private List<List<String>> snapshot(Fixture f) {
        var result = new ArrayList<List<String>>();
        for (String table : List.of("oauth2_registered_client", "registered_client_metadata", "registered_client_backchannel_logout_uris",
                "oauth2_authorization", "oauth2_authorization_consent", "oauth2_authorization_security", "security_events", "admin_audit_events", "outbox_events")) {
            String predicate = switch (table) {
                case "oauth2_registered_client" -> "client_id = :client";
                case "registered_client_metadata", "registered_client_backchannel_logout_uris", "oauth2_authorization", "oauth2_authorization_consent" -> "registered_client_id = :registered";
                case "oauth2_authorization_security" -> "authorization_id = :authorization";
                case "security_events" -> "client_id = :client";
                case "admin_audit_events" -> "target_id = :client";
                default -> "payload_json->>'clientId' = :client";
            };
            var query = jdbc.sql("SELECT to_jsonb(t)::text FROM " + table + " t WHERE " + predicate + " ORDER BY to_jsonb(t)::text");
            if (predicate.contains(":registered")) query.param("registered", f.registeredId());
            if (predicate.contains(":authorization")) query.param("authorization", f.authorizationId());
            if (predicate.contains(":client")) query.param("client", f.clientId());
            result.add(query.query(String.class).list());
        }
        return result;
    }

    private enum Mutation {
        NAME("ADMIN_CLIENT_UPDATE"), SHRINK("ADMIN_CLIENT_UPDATE"), ROTATE("ADMIN_CLIENT_SECRET_ROTATE"), DISABLE("ADMIN_CLIENT_DISABLE"), ENABLE("ADMIN_CLIENT_ENABLE");
        private final String action;
        Mutation(String action) { this.action = action; }
        String action() { return action; }
    }
    private record Fixture(String clientId, String registeredId, String secret, UUID actorId, UUID userId, String authorizationId) { }
}
