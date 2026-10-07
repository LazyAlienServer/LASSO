package org.las.sso.maintenance;

import java.io.Console;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "las.sso.maintenance.command", havingValue = "admin-bootstrap")
public final class AdminBootstrapCommand implements ApplicationRunner {

    private final AdminBootstrapService service;
    private final Environment environment;
    private final Supplier<Console> consoleSupplier;

    @Autowired
    public AdminBootstrapCommand(AdminBootstrapService service, Environment environment) {
        this(service, environment, System::console);
    }

    /**
     * Console acquisition seam; production still requires a real hidden-input interactive console.
     */
    AdminBootstrapCommand(
            AdminBootstrapService service,
            Environment environment,
            Supplier<Console> consoleSupplier) {
        this.service = service;
        this.environment = environment;
        this.consoleSupplier = consoleSupplier;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        requireNonWebMode();
        if (arguments.containsOption("password")) {
            throw new AdminBootstrapException("PASSWORD_OPTION_NOT_ALLOWED");
        }
        String username = singleOption(arguments, "username");
        String userId = singleOption(arguments, "user-id");
        if ((username == null) == (userId == null)) {
            throw new AdminBootstrapException("SPECIFY_EXACTLY_ONE_OF_USERNAME_OR_USER_ID");
        }

        Console console = consoleSupplier.get();
        if (console == null) {
            throw new AdminBootstrapException("INTERACTIVE_CONSOLE_REQUIRED");
        }
        char[] first = console.readPassword("Temporary administrator password: ");
        char[] second = console.readPassword("Repeat temporary administrator password: ");
        try {
            if (first == null || second == null || !Arrays.equals(first, second)) {
                throw new AdminBootstrapException("PASSWORD_CONFIRMATION_MISMATCH");
            }
            String temporaryPassword = new String(first);
            AdminBootstrapService.BootstrapResult result =
                    username != null
                            ? service.createFirstUser(username, username, temporaryPassword)
                            : service.promoteExistingUser(
                                    UUID.fromString(userId), temporaryPassword);
            console.printf(
                    "Administrator bootstrap completed.%nusername=%s%nuserId=%s%ntemporaryPasswordExpiresAt=%s%nauditEventId=%s%n",
                    result.username(),
                    result.userId(),
                    result.temporaryPasswordExpiresAt(),
                    result.auditEventId());
        } catch (AdminBootstrapException exception) {
            if ("DELETION_CANCELLATION_REQUIRED".equals(exception.code())) {
                console.printf(
                        "Administrator promotion refused (DELETION_CANCELLATION_REQUIRED).%n"
                                + "The account owner must cancel the pending/held deletion request first; "
                                + "then retry administrator bootstrap.%n");
            }
            throw exception; // Preserve unsuccessful ApplicationRunner/startup, never report
            // bootstrap success.
        } finally {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
        }
    }

    private void requireNonWebMode() {
        String type = environment.getProperty("spring.main.web-application-type", "");
        if (!"none".equalsIgnoreCase(type)) {
            throw new AdminBootstrapException("MAINTENANCE_COMMAND_REQUIRES_NON_WEB_MODE");
        }
    }

    private static String singleOption(ApplicationArguments arguments, String name) {
        List<String> values = arguments.getOptionValues(name);
        if (values == null) {
            return null;
        }
        if (values.size() != 1 || values.getFirst().isBlank()) {
            throw new AdminBootstrapException(
                    "INVALID_OPTION_" + name.toUpperCase(java.util.Locale.ROOT));
        }
        return values.getFirst();
    }
}
