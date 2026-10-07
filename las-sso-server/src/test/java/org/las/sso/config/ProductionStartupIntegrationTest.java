package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.las.sso.LasSsoApplication;
import org.las.sso.admin.AdminClientService;
import org.las.sso.maintenance.AdminBootstrapService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real non-development config, persistent key, PostgreSQL and servlet startup; no public TLS proxy.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProductionStartupIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine")
                    .withPassword(ProductionConfigurationGateTest.SECRET + "-database");

    @TempDir static Path keyDirectory;
    static final String SECRET = ProductionConfigurationGateTest.SECRET;
    static Path key;

    @BeforeAll
    static void signingKey() throws Exception {
        key = keyDirectory.resolve("signing.p12");
        createKey(key, 2048);
    }

    @Test
    void emptyDatabaseCannotOpenWebButNonWebBootstrapCreatesAdminThenProductionStarts()
            throws Exception {
        // This test owns the disposable DB for its whole sequence; no local client seeder runs.
        try (ConfigurableApplicationContext context = start(false, Map.of())) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            jdbc.sql("TRUNCATE users CASCADE").update();
            jdbc.sql("DELETE FROM oauth2_registered_client").update();
        }
        int rejectedPort;
        try (var socket = new ServerSocket(0)) {
            rejectedPort = socket.getLocalPort();
        }
        assertThatThrownBy(
                        () ->
                                start(
                                        true,
                                        Map.of(
                                                "spring.main.lazy-initialization",
                                                "true",
                                                "server.port",
                                                Integer.toString(rejectedPort))))
                .hasStackTraceContaining("PRODUCTION_ADMIN_INITIALIZATION_REQUIRED")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(SECRET));
        assertThatThrownBy(
                        () -> {
                            try (var socket = new Socket("127.0.0.1", rejectedPort)) {}
                        })
                .isInstanceOf(java.io.IOException.class);
        try (ConfigurableApplicationContext context = start(false, Map.of())) {
            var result =
                    context.getBean(AdminBootstrapService.class)
                            .createFirstUser(
                                    "ProductionAdmin",
                                    "Production Administrator",
                                    "fresh production administrator phrase");
            assertThat(result.temporaryPasswordExpiresAt()).isNotNull();
        }
        try (ConfigurableApplicationContext context = start(true, Map.of())) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertThat(get(client, port, "/actuator/health/readiness").statusCode()).isEqualTo(200);
            assertThat(get(client, port, "/actuator/health/liveness").statusCode()).isEqualTo(200);
            assertThat(get(client, port, "/login").statusCode()).isEqualTo(200);
            assertThat(get(client, port, "/.well-known/openid-configuration").body())
                    .contains("https://auth.las.internal");
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            assertThat(
                            jdbc.sql("SELECT COUNT(*) FROM oauth2_registered_client")
                                    .query(Long.class)
                                    .single())
                    .isZero();
            assertThat(
                            jdbc.sql("SELECT state::text FROM password_credentials")
                                    .query(String.class)
                                    .single())
                    .isEqualTo("PASSWORD_CHANGE_REQUIRED");
        }
        try (ConfigurableApplicationContext context = start(false, Map.of())) {
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            jdbc.sql(
                            "UPDATE password_credentials SET temporary_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'")
                    .update();
        }
        assertThatThrownBy(() -> start(true, Map.of()))
                .hasStackTraceContaining("PRODUCTION_ADMIN_INITIALIZATION_REQUIRED");
        assertThatThrownBy(
                        () ->
                                start(
                                        true,
                                        Map.of(
                                                "las.sso.signing-key.password",
                                                SECRET + "-wrong",
                                                "spring.main.lazy-initialization",
                                                "true")))
                .hasStackTraceContaining("Unable to load persistent signing key");
        try (ConfigurableApplicationContext context = start(false, Map.of())) {
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            jdbc.sql(
                            "UPDATE password_credentials SET temporary_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 day'")
                    .update();
            jdbc.sql(
                            "UPDATE password_credentials SET state = 'ACTIVE', temporary_expires_at = NULL")
                    .update();
            var actor =
                    jdbc.sql("SELECT id FROM users WHERE is_sso_admin = true")
                            .query(java.util.UUID.class)
                            .single();
            assertThatThrownBy(
                            () ->
                                    context.getBean(AdminClientService.class)
                                            .create(
                                                    new AdminClientService.CreateRequest(
                                                            "reserved-client",
                                                            "Reserved Client",
                                                            "FIRST_PARTY",
                                                            java.util.List.of(
                                                                    "https://client.example/callback"),
                                                            java.util.List.of(),
                                                            java.util.List.of("openid"),
                                                            java.util.List.of(
                                                                    "urn:las:resource:reserved"),
                                                            null),
                                                    actor,
                                                    null,
                                                    "reserved fixture",
                                                    java.util.UUID.randomUUID()))
                    .hasMessage("INVALID_REDIRECT_URI");
            context.getBean(AdminClientService.class)
                    .create(
                            new AdminClientService.CreateRequest(
                                    "production-client",
                                    "Production Client",
                                    "FIRST_PARTY",
                                    java.util.List.of(
                                            "https://business.las.internal/login/oauth2/code/las-sso"),
                                    java.util.List.of("https://business.las.internal/logged-out"),
                                    java.util.List.of("openid", "profile"),
                                    java.util.List.of("urn:las:resource:business"),
                                    "https://business.las.internal/internal/oidc/backchannel-logout"),
                            actor,
                            null,
                            "startup fixture",
                            java.util.UUID.randomUUID());
        }
        try (ConfigurableApplicationContext context = start(true, Map.of())) {
            assertThat(((WebServerApplicationContext) context).getWebServer().getPort())
                    .isPositive();
        }
        try (ConfigurableApplicationContext context = start(false, Map.of())) {
            context.getBean(JdbcClient.class)
                    .sql(
                            "UPDATE oauth2_registered_client SET redirect_uris = 'http://127.0.0.1:8088/callback'")
                    .update();
        }
        assertThatThrownBy(() -> start(true, Map.of()))
                .hasStackTraceContaining("PRODUCTION_CLIENT_CONFIGURATION_INVALID");
        try (ConfigurableApplicationContext context = start(false, Map.of())) {
            context.getBean(JdbcClient.class)
                    .sql("UPDATE registered_client_metadata SET status = 'DISABLED'")
                    .update();
        }
        try (ConfigurableApplicationContext context = start(true, Map.of())) {
            assertThat(((WebServerApplicationContext) context).getWebServer().getPort())
                    .isPositive();
        }
        try (ConfigurableApplicationContext context = start(false, Map.of())) {
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            jdbc.sql("UPDATE registered_client_metadata SET status = 'ACTIVE'").update();
            jdbc.sql(
                            "UPDATE oauth2_registered_client SET redirect_uris = 'https://business.las.internal/callback'")
                    .update();
            jdbc.sql("UPDATE oauth2_registered_client SET client_secret = :hash")
                    .param(
                            "hash",
                            context.getBean(
                                            org.springframework.security.crypto.password
                                                    .PasswordEncoder.class)
                                    .encode("laslog-local-only"))
                    .update();
        }
        assertThatThrownBy(() -> start(true, Map.of()))
                .hasStackTraceContaining("PRODUCTION_CLIENT_EXAMPLE_SECRET");
        try (ConfigurableApplicationContext context = start(false, Map.of())) {
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            jdbc.sql("UPDATE registered_client_metadata SET status = 'DISABLED'").update();
            jdbc.sql("UPDATE system_security_state SET security_epoch = '' WHERE id = 1").update();
        }
        assertThatThrownBy(() -> start(true, Map.of()))
                .hasStackTraceContaining("PRODUCTION_SECURITY_STATE_INVALID");
        // The fixture owns the DB; restore its epoch before testing the real command's console
        // refusal.
        var dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcClient.create(dataSource)
                .sql(
                        "UPDATE system_security_state SET security_epoch = 'restored-fixture-epoch' WHERE id = 1")
                .update();
        assertThatThrownBy(
                        () ->
                                start(
                                        false,
                                        Map.of(
                                                "fixture.run-command",
                                                "true",
                                                "username",
                                                "ConsoleFixture")))
                .hasStackTraceContaining("INTERACTIVE_CONSOLE_REQUIRED");
        JdbcClient.create(dataSource)
                .sql(
                        """
                INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                VALUES (999, '999', 'future fixture', 'SQL', 'V999__future_fixture.sql', 1, 'fixture', 1, true)
                """)
                .update();
        assertThatThrownBy(() -> start(false, Map.of()))
                .hasStackTraceContaining("PRODUCTION_SCHEMA_INCOMPATIBLE");
        JdbcClient.create(dataSource)
                .sql("DELETE FROM flyway_schema_history WHERE installed_rank = 999")
                .update();
        try (var context =
                start(
                        true,
                        Map.of(
                                "server.servlet.context-path",
                                "/passport",
                                "las.sso.issuer",
                                "https://auth.las.internal/passport"))) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertThat(get(client, port, "/passport/.well-known/openid-configuration").statusCode())
                    .isEqualTo(200);
            assertThat(get(client, port, "/passport/.well-known/openid-configuration").body())
                    .contains("https://auth.las.internal/passport");
        }
    }

    @Test
    void realPersistentKeyLoadAcceptsStrongKeyAndRejectsWrongPasswordAliasAndSmallKey()
            throws Exception {
        var configuration = new PersistentSigningKeyConfiguration();
        assertThat(configuration.persistentJwkSource(properties(key, SECRET + "-key", "signing")))
                .isNotNull();
        assertThatThrownBy(
                        () ->
                                configuration.persistentJwkSource(
                                        properties(key, SECRET + "-wrong", "signing")))
                .hasMessage("Unable to load persistent signing key")
                .hasNoCause();
        assertThatThrownBy(
                        () ->
                                configuration.persistentJwkSource(
                                        properties(key, SECRET + "-key", "missing")))
                .hasMessage("Unable to load persistent signing key")
                .hasNoCause();
        Path small = keyDirectory.resolve("small.p12");
        createKey(small, 1024);
        assertThatThrownBy(
                        () ->
                                configuration.persistentJwkSource(
                                        properties(small, SECRET + "-key", "signing")))
                .hasMessage("Unable to load persistent signing key")
                .hasNoCause();
    }

    private static LasSsoProperties properties(Path path, String password, String alias) {
        return new LasSsoProperties(
                URI.create("https://auth.las.internal"),
                new LasSsoProperties.SigningKey(
                        path.toString(), password, alias, "production-fixture"));
    }

    private static ConfigurableApplicationContext start(
            boolean web, Map<String, String> overrides) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        settings.put("spring.datasource.username", POSTGRES.getUsername());
        settings.put("spring.datasource.password", POSTGRES.getPassword());
        settings.put("las.sso.issuer", "https://auth.las.internal");
        settings.put("las.sso.signing-key.path", key.toString());
        settings.put("las.sso.signing-key.password", SECRET + "-key");
        settings.put("las.sso.signing-key.alias", "signing");
        settings.put("las.sso.signing-key.key-id", "production-fixture");
        settings.put("las.sso.recovery.pepper", SECRET + "-recovery");
        settings.put("las.sso.transient-security.key-pepper", SECRET + "-transient");
        settings.put("las.sso.transient-security.captcha-hmac-secret", SECRET + "-captcha");
        settings.put("las.sso.scheduling.enabled", "false");
        settings.put("spring.session.jdbc.cleanup-cron", "-");
        settings.put("spring.main.web-application-type", web ? "servlet" : "none");
        if (!web) {
            // Configure the gate's recognized maintenance path without invoking an interactive
            // command.
            settings.put("las.sso.maintenance.command", "admin-bootstrap");
            settings.put("spring.main.lazy-initialization", "false");
        }
        settings.put("server.address", "127.0.0.1");
        settings.put("server.port", "0");
        settings.put("server.forward-headers-strategy", "none");
        settings.put("spring.main.banner-mode", "off");
        settings.putAll(overrides);
        var args = new ArrayList<String>();
        settings.forEach((name, value) -> args.add("--" + name + "=" + value));
        var application =
                new SpringApplicationBuilder(LasSsoApplication.class)
                        .web(web ? WebApplicationType.SERVLET : WebApplicationType.NONE)
                        .profiles("production")
                        .initializers(
                                context ->
                                        context.addBeanFactoryPostProcessor(
                                                factory -> {
                                                    // Tests exercise startup wiring/services, not a
                                                    // substituted hidden-input console.
                                                    if (!web
                                                            && !"true"
                                                                    .equals(
                                                                            context.getEnvironment()
                                                                                    .getProperty(
                                                                                            "fixture.run-command"))
                                                            && factory.containsBeanDefinition(
                                                                    "adminBootstrapCommand")) {
                                                        ((org.springframework.beans.factory.support
                                                                                .BeanDefinitionRegistry)
                                                                        factory)
                                                                .removeBeanDefinition(
                                                                        "adminBootstrapCommand");
                                                    }
                                                }));
        return application.run(args.toArray(String[]::new));
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void createKey(Path path, int bits) throws Exception {
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var process =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                        "-genkeypair",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        Integer.toString(bits),
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        path.toString(),
                        "-alias",
                        "signing",
                        "-dname",
                        "CN=StartupFixtureOnly",
                        "-validity",
                        "2",
                        "-storepass:env",
                        "KEY_FIXTURE_PASSWORD",
                        "-keypass:env",
                        "KEY_FIXTURE_PASSWORD");
        process.environment().put("KEY_FIXTURE_PASSWORD", SECRET + "-key");
        var running = process.redirectErrorStream(true).start();
        running.getInputStream().readAllBytes();
        assertThat(running.waitFor()).isZero();
    }
}
