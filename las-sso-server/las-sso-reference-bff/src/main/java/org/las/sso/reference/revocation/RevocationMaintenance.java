package org.las.sso.reference.revocation;

import java.io.Console;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Minimal offline context: no Web/OIDC auto-configuration, scheduler or token network calls. */
public final class RevocationMaintenance {
    public static void run(String[] arguments) {
        SpringApplication application = new SpringApplication(OfflineConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("durable-revocation", "revocation-maintenance");
        try (var context = application.run(arguments)) {
            SpringApplication.exit(context);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("revocation-maintenance")
    @Import(RevocationStorageConfiguration.class)
    static class OfflineConfiguration {
        @Bean
        ApplicationRunner maintenance(
                JdbcRevocationStore store,
                RevocationTokenCipher cipher,
                RevocationProperties properties,
                Environment environment) {
            return new Command(store, cipher, properties, environment, System::console);
        }
    }

    static final class Command implements ApplicationRunner {
        private final JdbcRevocationStore store;
        private final RevocationTokenCipher cipher;
        private final RevocationProperties properties;
        private final Environment environment;
        private final Supplier<Console> console;

        Command(
                JdbcRevocationStore store,
                RevocationTokenCipher cipher,
                RevocationProperties properties,
                Environment environment,
                Supplier<Console> console) {
            this.store = store;
            this.cipher = cipher;
            this.properties = properties;
            this.environment = environment;
            this.console = console;
        }

        @Override
        public void run(ApplicationArguments arguments) {
            if (!"none"
                            .equalsIgnoreCase(
                                    environment.getProperty("spring.main.web-application-type", ""))
                    || !"false"
                            .equalsIgnoreCase(
                                    environment.getProperty(
                                            "reference.revocation.worker-enabled", "")))
                throw new IllegalStateException("REVOCATION_MAINTENANCE_REQUIRES_OFFLINE_MODE");
            String command = option(arguments, "command");
            for (String name :
                    Set.of(
                            "next-key-base64",
                            "reference.revocation.next-key-base64",
                            "reference.revocation.key-base64",
                            "reference.revocation.password")) {
                if (arguments.containsOption(name))
                    throw new IllegalStateException("REVOCATION_SECRET_OPTION_NOT_ALLOWED");
            }
            if (!Set.of("replay", "rotate-key", "purge-completed").contains(command))
                throw new IllegalStateException("REVOCATION_MAINTENANCE_COMMAND_INVALID");
            String actor = option(arguments, "actor");
            String reason = option(arguments, "reason");
            if (!cipher.keyId().equals(option(arguments, "expected-key-id")))
                throw new IllegalStateException("REVOCATION_EXPECTED_KEY_MISMATCH");
            UUID targetTask = null;
            int replayGeneration = 0;
            String nextId = null;
            try {
                if ("replay".equals(command)) {
                    targetTask = UUID.fromString(option(arguments, "task-id"));
                    replayGeneration = Integer.parseInt(option(arguments, "expected-replay-count"));
                    if (replayGeneration < 0 || replayGeneration == Integer.MAX_VALUE)
                        throw new IllegalArgumentException();
                }
                if ("rotate-key".equals(command)) {
                    nextId = option(arguments, "next-key-id");
                    if (!nextId.matches("[A-Za-z0-9._-]{1,64}"))
                        throw new IllegalArgumentException();
                }
            } catch (RuntimeException exception) {
                throw new IllegalStateException("REVOCATION_TARGET_INVALID");
            }
            Console terminal = console.get();
            if (terminal == null) throw new IllegalStateException("INTERACTIVE_CONSOLE_REQUIRED");
            terminal.printf("Current key ID: %s%n", cipher.keyId());
            if (targetTask != null)
                terminal.printf(
                        "Dead task ID: %s; expected replay generation: %d%n",
                        targetTask, replayGeneration);
            if (nextId != null) terminal.printf("New key ID: %s%n", nextId);
            if (!"YES"
                    .equals(
                            terminal.readLine(
                                    "Offline reference revocation %s. Type YES to confirm: ",
                                    command)))
                throw new IllegalStateException("REVOCATION_MAINTENANCE_NOT_CONFIRMED");
            try {
                switch (command) {
                    case "replay" -> store.replay(targetTask, replayGeneration, actor, reason);
                    case "rotate-key" -> {
                        // The new key is external configuration only; never accept it as an option.
                        var next =
                                new RevocationTokenCipher(
                                        nextId,
                                        environment.getRequiredProperty(
                                                "reference.revocation.next-key-base64"));
                        store.rotateKey(next, actor, reason);
                    }
                    case "purge-completed" ->
                            store.purgeCompleted(
                                    properties.completedRetention(),
                                    properties.cleanupBatchSize(),
                                    actor,
                                    reason);
                    default ->
                            throw new IllegalStateException(
                                    "REVOCATION_MAINTENANCE_COMMAND_INVALID");
                }
            } catch (RuntimeException exception) {
                // SQL/config parsing exceptions can contain external values; do not retain a cause.
                if (Set.of(
                                "DEAD_TASK_OR_GENERATION_MISMATCH",
                                "KEY_DRAIN_REQUIRED",
                                "NEW_KEY_ID_REQUIRED",
                                "NEW_KEY_MATERIAL_REQUIRED",
                                "KEY_ID_REUSE_FORBIDDEN",
                                "KEY_CONFIGURATION_CHANGED",
                                "INVALID_OPERATOR_REASON",
                                "Revocation payload unavailable")
                        .contains(exception.getMessage() == null ? "" : exception.getMessage()))
                    throw new IllegalStateException(
                            "REVOCATION_MAINTENANCE_FAILED:" + exception.getMessage());
                throw new IllegalStateException("REVOCATION_MAINTENANCE_FAILED");
            }
            terminal.printf("Reference revocation maintenance committed: action=%s%n", command);
        }

        private static String option(ApplicationArguments arguments, String name) {
            var values = arguments.getOptionValues(name);
            if (values == null || values.size() != 1 || values.getFirst().isBlank())
                throw new IllegalStateException("REVOCATION_OPTION_REQUIRED:" + name);
            return values.getFirst();
        }
    }

    private RevocationMaintenance() {}
}
