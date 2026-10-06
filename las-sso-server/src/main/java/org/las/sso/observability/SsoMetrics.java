package org.las.sso.observability;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Process-local observations. Tag values are closed categories, never identity or request data. */
@Component
public final class SsoMetrics {

    public enum LoginResult { SUCCESS, REJECTED, LIMITED, CAPTCHA_REQUIRED }
    public enum LoginReason { NONE, CREDENTIALS, LOCKED, DISABLED, EXPIRED, NETWORK, CAPTCHA, OTHER }
    public enum QueryResult { SUCCESS, INVALID_CREDENTIALS, LIMITED, UNAVAILABLE }
    public enum AccessDecision { ALLOWED, DENIED, UNAVAILABLE }
    public enum RefreshResult { SUCCESS, REJECTED, ERROR }
    public enum DeliveryResult { SUCCESS, RETRY, DEAD }
    public enum HashOperation { ENCODE, MATCH }
    public enum HashResult { SUCCESS, MISMATCH, ERROR }
    public enum HashAlgorithm { ARGON2ID, BCRYPT, OTHER }

    private final MeterRegistry registry;

    public SsoMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (LoginResult result : LoginResult.values()) {
            for (LoginReason reason : LoginReason.values()) {
                registry.counter("las.sso.login.attempts", "result", tag(result), "reason", tag(reason));
            }
        }
        for (QueryResult result : QueryResult.values()) registry.counter("las.sso.internal.authentication", "result", tag(result));
        for (AccessDecision decision : AccessDecision.values()) registry.counter("las.sso.service.access", "decision", tag(decision));
        for (RefreshResult result : RefreshResult.values()) registry.counter("las.sso.token.refresh", "result", tag(result));
        for (DeliveryResult result : DeliveryResult.values()) registry.counter("las.sso.backchannel.delivery", "result", tag(result));
        registry.counter("las.sso.refresh.reuse");
        registry.counter("las.sso.admin.login.failures");
        registry.counter("las.sso.registration.success");
    }

    public void login(LoginResult result, LoginReason reason) {
        registry.counter("las.sso.login.attempts", "result", tag(result), "reason", tag(reason)).increment();
    }

    public void internalAuthentication(QueryResult result) {
        registry.counter("las.sso.internal.authentication", "result", tag(result)).increment();
    }

    public void serviceAccess(AccessDecision decision) {
        registry.counter("las.sso.service.access", "decision", tag(decision)).increment();
    }

    public void refresh(RefreshResult result) {
        registry.counter("las.sso.token.refresh", "result", tag(result)).increment();
    }

    public void delivery(DeliveryResult result) {
        afterCommit(() -> registry.counter("las.sso.backchannel.delivery", "result", tag(result)).increment());
    }

    public void committedRefreshReuse() {
        afterCommit(() -> registry.counter("las.sso.refresh.reuse").increment());
    }

    public void committedAdminLoginFailure() {
        afterCommit(() -> registry.counter("las.sso.admin.login.failures").increment());
    }

    public void committedRegistrationSuccess() {
        afterCommit(() -> registry.counter("las.sso.registration.success").increment());
    }

    public void hash(HashOperation operation, HashAlgorithm algorithm, HashResult result, long nanos) {
        Timer.builder("las.sso.password.hash")
                .tags("operation", tag(operation), "algorithm", tag(algorithm), "result", tag(result))
                .serviceLevelObjectives(Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
                        Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2))
                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    private static void afterCommit(Runnable observation) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { observation.run(); }
            });
        } else {
            observation.run();
        }
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }
}
