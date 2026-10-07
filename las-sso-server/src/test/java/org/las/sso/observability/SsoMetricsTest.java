package org.las.sso.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SsoMetricsTest {

    @Test
    void rolledBackSecurityMutationDoesNotCountCommittedReuse() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new SsoMetrics(registry);
            TransactionSynchronizationManager.initSynchronization();
            try {
                metrics.committedRefreshReuse();
                var synchronization =
                        TransactionSynchronizationManager.getSynchronizations().getFirst();
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
                assertThat(registry.get("las.sso.refresh.reuse").counter().count()).isZero();
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void securityCounterIsDeferredUntilCommitCallback() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new SsoMetrics(registry);
            TransactionSynchronizationManager.initSynchronization();
            try {
                metrics.committedRefreshReuse();
                assertThat(registry.get("las.sso.refresh.reuse").counter().count()).isZero();
                TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
                assertThat(registry.get("las.sso.refresh.reuse").counter().count()).isEqualTo(1);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void encoderPreservesResultUpgradeAndExceptionsWithoutSecretTags() {
        var registry = new SimpleMeterRegistry();
        try {
            var encoder =
                    new MeteredPasswordEncoder(
                            new PasswordEncoder() {
                                public String encode(CharSequence raw) {
                                    return "{argon2id}fixture";
                                }

                                public boolean matches(CharSequence raw, String encoded) {
                                    if (encoded.equals("malformed"))
                                        throw new IllegalArgumentException("fixture");
                                    return raw.toString().equals("correct-secret");
                                }

                                public boolean upgradeEncoding(String encoded) {
                                    return true;
                                }
                            },
                            new SsoMetrics(registry));
            assertThat(encoder.encode("correct-secret")).isEqualTo("{argon2id}fixture");
            assertThat(encoder.matches("correct-secret", "{argon2id}fixture")).isTrue();
            assertThat(encoder.matches("incorrect-secret", "{argon2id}fixture")).isFalse();
            assertThat(encoder.upgradeEncoding("{argon2id}fixture")).isTrue();
            assertThatThrownBy(() -> encoder.matches("correct-secret", "malformed"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(registry.getMeters())
                    .allSatisfy(
                            meter ->
                                    assertThat(meter.getId().getTags().toString())
                                            .doesNotContain("secret", "fixture", "malformed"));
            assertThat(
                            registry.get("las.sso.password.hash")
                                    .tags("operation", "match", "result", "success")
                                    .timer()
                                    .count())
                    .isEqualTo(1);
            assertThat(
                            registry.get("las.sso.password.hash")
                                    .tags("operation", "match", "result", "mismatch")
                                    .timer()
                                    .count())
                    .isEqualTo(1);
            assertThat(
                            registry.get("las.sso.password.hash")
                                    .tags("operation", "match", "result", "error")
                                    .timer()
                                    .count())
                    .isEqualTo(1);
        } finally {
            registry.close();
        }
    }
}
