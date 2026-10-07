package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationGateTest {
    static final String SECRET = "secret-canary-production-configuration-0123456789";

    @Test
    void validProductionSettingsAndPrefixIssuerAreAccepted() {
        assertThatCode(() -> ProductionConfigurationGate.validate(valid()))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                ProductionConfigurationGate.validate(
                                        valid().withProperty(
                                                        "las.sso.issuer",
                                                        "https://auth.las.internal/passport")
                                                .withProperty(
                                                        "server.servlet.context-path",
                                                        "/passport")))
                .doesNotThrowAnyException();
    }

    @Test
    void localAndTestRetainDevelopmentBehaviorButCannotMixWithProduction() {
        for (String profile : new String[] {"local", "test"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);
            assertThatCode(() -> ProductionConfigurationGate.validate(env))
                    .doesNotThrowAnyException();
            env.setActiveProfiles(profile, "production");
            assertThatThrownBy(() -> ProductionConfigurationGate.validate(env))
                    .hasMessage("PRODUCTION_PROFILE_CONFLICT");
        }
        assertThatThrownBy(() -> ProductionConfigurationGate.validate(new MockEnvironment()))
                .hasMessage("PRODUCTION_PROFILE_REQUIRED");
    }

    @ParameterizedTest
    @MethodSource("unsafeSettings")
    void rejectsUnsafeSettingsWithoutRevealingTheirValues(String name, String value) {
        assertThatThrownBy(
                        () ->
                                ProductionConfigurationGate.validate(
                                        valid().withProperty(name, value)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(SECRET));
    }

    static Stream<Arguments> unsafeSettings() {
        return Stream.of(
                Arguments.of("las.sso.issuer", "http://auth.example.com"),
                Arguments.of("las.sso.issuer", "https://auth.example.com"),
                Arguments.of("las.sso.issuer", "https://auth.las.example"),
                Arguments.of("las.sso.issuer", "https://localhost"),
                Arguments.of("las.sso.issuer", "https://127.0.0.1"),
                Arguments.of("las.sso.issuer", "https://[::1]"),
                Arguments.of("las.sso.issuer", "https://user:" + SECRET + "@auth.las.internal"),
                Arguments.of("las.sso.issuer", "https://auth.las.internal/?secret=" + SECRET),
                Arguments.of("las.sso.issuer", "https://auth.las.internal/#fragment"),
                Arguments.of("las.sso.issuer", "https://auth.las.internal/a/../b"),
                Arguments.of("las.sso.issuer", "https://auth.las.internal/"),
                Arguments.of("las.sso.issuer", "https://auth.las.internal/passport"),
                Arguments.of("las.sso.signing-key.key-id", "las-local-ephemeral"),
                Arguments.of("las.sso.signing-key.path", ""),
                Arguments.of("las.sso.signing-key.password", "change-me-" + SECRET),
                Arguments.of("las.sso.recovery.pepper", "short"),
                Arguments.of("las.sso.recovery.pepper", "${MISSING_SECRET_CANARY}"),
                Arguments.of("las.sso.recovery.pepper", "local-only-" + SECRET),
                Arguments.of("las.sso.transient-security.key-pepper", SECRET + "-recovery"),
                Arguments.of("spring.datasource.password", "local-only-change-me"),
                Arguments.of(
                        "spring.datasource.url",
                        "jdbc:postgresql://db:5432/sso?password=" + SECRET),
                Arguments.of("spring.flyway.enabled", "false"),
                Arguments.of("spring.flyway.validate-on-migrate", "false"),
                Arguments.of("spring.flyway.clean-disabled", "false"),
                Arguments.of("spring.flyway.locations", "filesystem:unreviewed-sql"),
                Arguments.of("spring.flyway.locations[0]", "filesystem:unreviewed-sql"),
                Arguments.of("spring.flyway.baseline-on-migrate", "true"),
                Arguments.of("spring.flyway.url", "jdbc:postgresql://other:5432/sso"),
                Arguments.of("spring.session.jdbc.initialize-schema", "always"),
                Arguments.of("server.servlet.session.cookie.secure", "false"),
                Arguments.of("server.servlet.session.cookie.http-only", "false"),
                Arguments.of("server.servlet.session.cookie.same-site", "none"),
                Arguments.of("server.servlet.session.cookie.path", "/passport"),
                Arguments.of("server.servlet.session.cookie.domain", "example.com"),
                Arguments.of("server.servlet.session.cookie.name", "JSESSIONID"),
                Arguments.of("management.endpoint.health.show-details", "always"),
                Arguments.of("management.endpoint.env.show-values", "always"),
                Arguments.of("management.endpoints.web.exposure.include", "*"),
                Arguments.of("management.endpoints.web.exposure.include[0]", "env"),
                Arguments.of(
                        "management.endpoint.health.group.readiness.include", "readinessState"),
                Arguments.of("management.health.db.enabled", "false"),
                Arguments.of("management.endpoint.health.group.readiness.exclude", "db"),
                Arguments.of("management.endpoint.health.group.readiness.exclude", "*"),
                Arguments.of(
                        "management.endpoint.health.group.liveness.include", "livenessState,db"),
                Arguments.of("management.endpoint.health.probes.enabled", "false"),
                Arguments.of("las.sso.monitoring.enabled", "true"),
                Arguments.of("spring.main.web-application-type", "reactive"),
                Arguments.of("las.sso.maintenance.command", "admin-bootstrap"));
    }

    @Test
    void recognizedMaintenanceRequiresExplicitNonWebAndNoJobsAndDoesNotRequireBrowserCookie() {
        MockEnvironment env =
                valid().withProperty("spring.main.web-application-type", "none")
                        .withProperty("las.sso.maintenance.command", "admin-bootstrap")
                        .withProperty("las.sso.scheduling.enabled", "false")
                        .withProperty("spring.session.jdbc.cleanup-cron", "-")
                        .withProperty("server.servlet.session.cookie.secure", "false");
        assertThatCode(() -> ProductionConfigurationGate.validate(env)).doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                ProductionConfigurationGate.validate(
                                        env.withProperty("las.sso.scheduling.enabled", "true")))
                .hasMessage("MAINTENANCE_REQUIRES_SCHEDULING_DISABLED");
        env.withProperty("las.sso.scheduling.enabled", "false");
        assertThatThrownBy(
                        () ->
                                ProductionConfigurationGate.validate(
                                        env.withProperty(
                                                "spring.session.jdbc.cleanup-cron", "0 * * * * *")))
                .hasMessage("MAINTENANCE_REQUIRES_SESSION_CLEANUP_DISABLED");
        env.withProperty("spring.session.jdbc.cleanup-cron", "-");
        assertThatThrownBy(
                        () ->
                                ProductionConfigurationGate.validate(
                                        env.withProperty("las.sso.monitoring.enabled", "true")))
                .hasMessage("MAINTENANCE_REQUIRES_MONITORING_DISABLED");
    }

    @Test
    void gateRunsBeforeSecretBindingAndDatabaseBeanCreation() {
        new ApplicationContextRunner()
                .withInitializer(
                        context ->
                                context.getEnvironment().setActiveProfiles("production", "local"))
                .withUserConfiguration(ProductionStartupConfiguration.class)
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasMessage("PRODUCTION_PROFILE_CONFLICT"));
    }

    @Test
    void monitoringRequiresValidSeparatePortAndDedicatedHashBeforeBinding() {
        MockEnvironment env =
                valid().withProperty("las.sso.monitoring.enabled", "true")
                        .withProperty("management.server.port", "9091")
                        .withProperty(
                                "las.sso.monitoring.password-hash",
                                "{bcrypt}$2b$10$" + "a".repeat(53));
        assertThatCode(() -> ProductionConfigurationGate.validate(env)).doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                ProductionConfigurationGate.validate(
                                        env.withProperty("management.server.port", "9000")))
                .hasMessage("PRODUCTION_MONITORING_PORT_INVALID");
        env.withProperty("management.server.port", "9091");
        assertThatThrownBy(
                        () ->
                                ProductionConfigurationGate.validate(
                                        env.withProperty(
                                                "las.sso.monitoring.password-hash", SECRET)))
                .hasMessage("PRODUCTION_MONITORING_CREDENTIAL_INVALID");
    }

    @Test
    void productionSecretsCannotReuseKeyOrDatabasePassword() {
        assertThatThrownBy(
                        () ->
                                ProductionConfigurationGate.validate(
                                        valid().withProperty(
                                                        "spring.datasource.password",
                                                        SECRET + "-key")))
                .hasMessage("PRODUCTION_SECRET_REUSE");
        assertThatThrownBy(
                        () ->
                                ProductionConfigurationGate.validate(
                                        valid().withProperty(
                                                        "las.sso.signing-key.password",
                                                        SECRET + "-recovery")))
                .hasMessage("PRODUCTION_SECRET_REUSE");
    }

    static MockEnvironment valid() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        return env.withProperty("las.sso.issuer", "https://auth.las.internal")
                .withProperty("las.sso.signing-key.key-id", "sso-prod-1")
                .withProperty("las.sso.signing-key.path", "protected-key.p12")
                .withProperty("las.sso.signing-key.alias", "signing")
                .withProperty("las.sso.signing-key.password", SECRET + "-key")
                .withProperty("las.sso.recovery.pepper", SECRET + "-recovery")
                .withProperty("las.sso.transient-security.key-pepper", SECRET + "-transient")
                .withProperty("las.sso.transient-security.captcha-hmac-secret", SECRET + "-captcha")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db:5432/sso")
                .withProperty("spring.datasource.username", "sso_runtime")
                .withProperty("spring.datasource.password", SECRET + "-database")
                .withProperty("server.servlet.session.cookie.name", "__Host-las_sso")
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("server.servlet.session.cookie.http-only", "true")
                .withProperty("server.servlet.session.cookie.same-site", "lax")
                .withProperty("server.servlet.session.cookie.path", "/");
    }
}
