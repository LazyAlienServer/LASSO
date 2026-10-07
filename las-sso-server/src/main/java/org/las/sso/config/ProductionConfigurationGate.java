package org.las.sso.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** Runs before configuration binding so rejected secret values are never part of binding errors. */
final class ProductionConfigurationGate {
    private static final Set<String> COMMANDS =
            Set.of(
                    "admin-bootstrap",
                    "admin-reset-password",
                    "rotate-security-epoch-after-restore");

    static void validate(Environment environment) {
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        boolean development = profiles.contains("local") || profiles.contains("test");
        if (development) {
            require(!profiles.contains("production"), "PRODUCTION_PROFILE_CONFLICT");
            return;
        }
        require(profiles.contains("production"), "PRODUCTION_PROFILE_REQUIRED");
        String command = environment.getProperty("las.sso.maintenance.command", "");
        String webType = environment.getProperty("spring.main.web-application-type", "servlet");
        boolean maintenance = !command.isBlank();
        require(
                "servlet".equalsIgnoreCase(webType) || "none".equalsIgnoreCase(webType),
                "UNSUPPORTED_WEB_MODE");
        require(!maintenance || COMMANDS.contains(command), "MAINTENANCE_COMMAND_INVALID");
        require(
                maintenance == "none".equalsIgnoreCase(webType),
                "MAINTENANCE_REQUIRES_NON_WEB_MODE");
        if (maintenance) {
            require(
                    is(environment, "las.sso.scheduling.enabled", "false", "true"),
                    "MAINTENANCE_REQUIRES_SCHEDULING_DISABLED");
            require(
                    is(environment, "spring.session.jdbc.cleanup-cron", "-", ""),
                    "MAINTENANCE_REQUIRES_SESSION_CLEANUP_DISABLED");
            require(
                    is(environment, "las.sso.monitoring.enabled", "false", "false"),
                    "MAINTENANCE_REQUIRES_MONITORING_DISABLED");
        }
        String issuer = value(environment, "las.sso.issuer");
        require(
                ProductionUriPolicy.isHttpsEndpoint(issuer, false)
                        && !issuer.endsWith("/")
                        && !issuer.contains("%")
                        && URI.create(issuer).getRawQuery() == null
                        && URI.create(issuer).normalize().toString().equals(issuer),
                "PRODUCTION_ISSUER_INVALID");
        require(
                URI.create(issuer)
                        .getPath()
                        .equals(environment.getProperty("server.servlet.context-path", "")),
                "PRODUCTION_ISSUER_CONTEXT_MISMATCH");
        String kid = value(environment, "las.sso.signing-key.key-id");
        require(kid.matches("[A-Za-z0-9._-]{1,128}") && !example(kid), "PRODUCTION_KEY_ID_INVALID");
        value(environment, "las.sso.signing-key.path");
        value(environment, "las.sso.signing-key.alias");
        String keyPassword = secret(environment, "las.sso.signing-key.password", 12);
        List<String> peppers =
                List.of(
                        secret(environment, "las.sso.recovery.pepper", 32),
                        secret(environment, "las.sso.transient-security.key-pepper", 32),
                        secret(environment, "las.sso.transient-security.captcha-hmac-secret", 32));
        require(Set.copyOf(peppers).size() == peppers.size(), "PRODUCTION_SECRET_REUSE");
        String jdbc = value(environment, "spring.datasource.url");
        require(
                jdbc.startsWith("jdbc:postgresql://")
                        && !jdbc.toLowerCase(Locale.ROOT).contains("password="),
                "PRODUCTION_DATASOURCE_INVALID");
        value(environment, "spring.datasource.username");
        String databasePassword = secret(environment, "spring.datasource.password", 12);
        require(
                !peppers.contains(keyPassword)
                        && !peppers.contains(databasePassword)
                        && !keyPassword.equals(databasePassword),
                "PRODUCTION_SECRET_REUSE");
        require(
                is(environment, "spring.flyway.enabled", "true", "true")
                        && is(environment, "spring.flyway.validate-on-migrate", "true", "true")
                        && is(environment, "spring.flyway.clean-disabled", "true", "true"),
                "PRODUCTION_FLYWAY_VALIDATION_REQUIRED");
        require(
                values(environment, "spring.flyway.locations", List.of("classpath:db/migration"))
                                .equals(List.of("classpath:db/migration"))
                        && is(environment, "spring.flyway.baseline-on-migrate", "false", "false"),
                "PRODUCTION_EMBEDDED_MIGRATIONS_REQUIRED");
        String migrationUrl = environment.getProperty("spring.flyway.url");
        require(
                !StringUtils.hasText(migrationUrl) || jdbc.equals(migrationUrl),
                "PRODUCTION_MIGRATION_DATASOURCE_MISMATCH");
        if (StringUtils.hasText(environment.getProperty("spring.flyway.password")))
            secret(environment, "spring.flyway.password", 12);
        require(
                is(environment, "spring.session.jdbc.initialize-schema", "never", "never"),
                "PRODUCTION_SESSION_SCHEMA_INVALID");
        if (!maintenance) {
            require(
                    is(environment, "server.servlet.session.cookie.secure", "true", "false")
                            && is(
                                    environment,
                                    "server.servlet.session.cookie.http-only",
                                    "true",
                                    "true")
                            && is(environment, "server.servlet.session.cookie.same-site", "lax", "")
                            && is(environment, "server.servlet.session.cookie.path", "/", "")
                            && "__Host-las_sso"
                                    .equals(
                                            value(
                                                    environment,
                                                    "server.servlet.session.cookie.name"))
                            && !StringUtils.hasText(
                                    environment.getProperty(
                                            "server.servlet.session.cookie.domain")),
                    "PRODUCTION_SESSION_COOKIE_INVALID");
            require(
                    is(environment, "management.endpoint.health.show-details", "never", "never"),
                    "PRODUCTION_HEALTH_DETAILS_INVALID");
            Set<String> readiness =
                    Set.copyOf(
                            values(
                                    environment,
                                    "management.endpoint.health.group.readiness.include",
                                    List.of("readinessState", "db")));
            require(
                    readiness.contains("readinessState") && readiness.contains("db"),
                    "PRODUCTION_DATABASE_READINESS_REQUIRED");
            require(
                    is(environment, "management.health.db.enabled", "true", "true")
                            && !values(
                                            environment,
                                            "management.endpoint.health.group.readiness.exclude",
                                            List.of())
                                    .contains("db"),
                    "PRODUCTION_DATABASE_READINESS_REQUIRED");
            require(
                    is(environment, "management.endpoint.health.probes.enabled", "true", "true")
                            && values(
                                            environment,
                                            "management.endpoint.health.group.readiness.exclude",
                                            List.of())
                                    .isEmpty()
                            && values(
                                            environment,
                                            "management.endpoint.health.group.liveness.include",
                                            List.of("livenessState"))
                                    .equals(List.of("livenessState"))
                            && values(
                                            environment,
                                            "management.endpoint.health.group.liveness.exclude",
                                            List.of())
                                    .isEmpty(),
                    "PRODUCTION_HEALTH_PROBES_INVALID");
            require(
                    !is(environment, "management.endpoint.env.show-values", "always", "never")
                            && !is(
                                    environment,
                                    "management.endpoint.configprops.show-values",
                                    "always",
                                    "never"),
                    "PRODUCTION_ACTUATOR_VALUES_INVALID");
            Set<String> exposed =
                    Set.copyOf(
                            values(
                                    environment,
                                    "management.endpoints.web.exposure.include",
                                    List.of("health", "info")));
            require(
                    Set.of("health", "info", "prometheus").containsAll(exposed),
                    "PRODUCTION_ACTUATOR_EXPOSURE_INVALID");
            if (is(environment, "las.sso.monitoring.enabled", "true", "false")) {
                String port = environment.getProperty("management.server.port", "");
                require(
                        port.matches("[0-9]{1,5}")
                                && Integer.parseInt(port) > 0
                                && Integer.parseInt(port) <= 65535
                                && !port.equals(environment.getProperty("server.port", "9000")),
                        "PRODUCTION_MONITORING_PORT_INVALID");
                String hash = value(environment, "las.sso.monitoring.password-hash");
                require(
                        hash.matches("\\{bcrypt}\\$2[aby]\\$(10|11|12|13|14)\\$[./A-Za-z0-9]{53}"),
                        "PRODUCTION_MONITORING_CREDENTIAL_INVALID");
            }
        }
    }

    private static String secret(Environment environment, String name, int minimumBytes) {
        String secret = value(environment, name);
        require(
                secret.getBytes(StandardCharsets.UTF_8).length >= minimumBytes && !example(secret),
                "PRODUCTION_SECRET_INVALID:" + name);
        return secret;
    }

    private static String value(Environment environment, String name) {
        String value;
        try {
            value = environment.getProperty(name);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PRODUCTION_SETTING_REQUIRED:" + name);
        }
        require(
                StringUtils.hasText(value) && !value.contains("${"),
                "PRODUCTION_SETTING_REQUIRED:" + name);
        return value;
    }

    private static boolean is(
            Environment environment, String name, String expected, String fallback) {
        return expected.equalsIgnoreCase(environment.getProperty(name, fallback));
    }

    private static List<String> values(
            Environment environment, String name, List<String> fallback) {
        try {
            return Binder.get(environment)
                    .bind(name, Bindable.listOf(String.class))
                    .orElse(fallback);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("PRODUCTION_SETTING_INVALID:" + name);
        }
    }

    private static boolean example(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("local-only")
                || lower.contains("test-only")
                || lower.contains("change-me")
                || lower.contains("changeme")
                || lower.startsWith("las-local")
                || lower.startsWith("las-test");
    }

    static void require(boolean valid, String code) {
        if (!valid) throw new IllegalStateException(code);
    }

    private ProductionConfigurationGate() {}
}
