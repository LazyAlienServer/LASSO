package org.las.sso.maintenance;

import java.io.Console;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "las.sso.maintenance.command",
        havingValue = "rotate-security-epoch-after-restore")
public final class SecurityEpochRecoveryCommand implements ApplicationRunner {
    private final SecurityEpochRecoveryService service;
    private final Environment environment;
    private final Supplier<Console> console;

    @Autowired
    public SecurityEpochRecoveryCommand(
            SecurityEpochRecoveryService service, Environment environment) {
        this(service, environment, System::console);
    }

    SecurityEpochRecoveryCommand(
            SecurityEpochRecoveryService service,
            Environment environment,
            Supplier<Console> console) {
        this.service = service;
        this.environment = environment;
        this.console = console;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!"none"
                .equalsIgnoreCase(
                        environment.getProperty("spring.main.web-application-type", ""))) {
            throw new SecurityEpochRecoveryException("MAINTENANCE_COMMAND_REQUIRES_NON_WEB_MODE");
        }
        if (!"false".equalsIgnoreCase(environment.getProperty("las.sso.scheduling.enabled", ""))) {
            throw new SecurityEpochRecoveryException("RECOVERY_REQUIRES_SCHEDULING_DISABLED");
        }
        String reason = one(arguments, "reason");
        if (reason.length() > 128)
            throw new SecurityEpochRecoveryException("INVALID_OPTION_REASON");
        String expectedEpoch = one(arguments, "expected-epoch");
        if (expectedEpoch.length() > 64)
            throw new SecurityEpochRecoveryException("INVALID_OPTION_EXPECTED_EPOCH");
        Console terminal = console.get();
        if (terminal == null)
            throw new SecurityEpochRecoveryException("INTERACTIVE_CONSOLE_REQUIRED");
        String confirmation =
                terminal.readLine(
                        "Type ROTATE-EPOCH-AFTER-RESTORE to invalidate restored security state: ");
        if (!"ROTATE-EPOCH-AFTER-RESTORE".equals(confirmation)) {
            throw new SecurityEpochRecoveryException("RECOVERY_CONFIRMATION_REQUIRED");
        }
        var result = service.rotateAfterRestore(expectedEpoch, reason);
        terminal.printf(
                "Security epoch rotated after restore.%noldEpoch=%s%nnewEpoch=%s%nrotatedAt=%s%n",
                result.oldEpoch(), result.newEpoch(), result.rotatedAt());
    }

    private static String one(ApplicationArguments arguments, String name) {
        List<String> values = arguments.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new SecurityEpochRecoveryException(
                    "INVALID_OPTION_" + name.toUpperCase(java.util.Locale.ROOT));
        }
        return values.getFirst();
    }
}
