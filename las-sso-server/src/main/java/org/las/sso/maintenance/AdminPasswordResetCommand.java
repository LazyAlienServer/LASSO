package org.las.sso.maintenance;

import java.io.Console;
import java.util.Arrays;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "las.sso.maintenance.command", havingValue = "admin-reset-password")
public final class AdminPasswordResetCommand implements ApplicationRunner {

    private final AdminPasswordResetService service;
    private final Environment environment;

    public AdminPasswordResetCommand(AdminPasswordResetService service, Environment environment) {
        this.service = service;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        requireNonWebMode();
        if (arguments.containsOption("password")) {
            throw new AdminPasswordResetException("PASSWORD_OPTION_NOT_ALLOWED");
        }
        Console console = System.console();
        if (console == null) {
            throw new AdminPasswordResetException("INTERACTIVE_CONSOLE_REQUIRED");
        }

        AdminPasswordResetService.Administrator administrator = service.currentAdministrator();
        console.printf(
                "Current SSO administrator:%nusername=%s%nuserId=%s%n",
                administrator.username(), administrator.userId());
        String confirmation = console.readLine("Reset this administrator password? Type YES to continue: ");
        if (!"YES".equals(confirmation)) {
            throw new AdminPasswordResetException("RESET_NOT_CONFIRMED");
        }

        char[] first = console.readPassword("New administrator password: ");
        char[] second = console.readPassword("Repeat new administrator password: ");
        try {
            if (first == null || second == null || !Arrays.equals(first, second)) {
                throw new AdminPasswordResetException("PASSWORD_CONFIRMATION_MISMATCH");
            }
            AdminPasswordResetService.ResetResult result = service.resetPassword(
                    administrator.userId(), new String(first));
            console.printf(
                    "Administrator password reset completed.%nusername=%s%nuserId=%s%nauditEventId=%s%n",
                    result.username(), result.userId(), result.auditEventId());
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
            throw new AdminPasswordResetException("MAINTENANCE_COMMAND_REQUIRES_NON_WEB_MODE");
        }
    }
}
