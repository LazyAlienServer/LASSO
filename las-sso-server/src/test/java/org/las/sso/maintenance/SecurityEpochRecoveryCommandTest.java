package org.las.sso.maintenance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.Console;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

/** Console is an injected fixture; actual interactive subprocess acceptance is separate. */
class SecurityEpochRecoveryCommandTest {
    private final SecurityEpochRecoveryService service = mock(SecurityEpochRecoveryService.class);
    private final Console console = mock(Console.class);
    private final MockEnvironment env = new MockEnvironment().withProperty("spring.main.web-application-type", "none")
            .withProperty("las.sso.scheduling.enabled", "false");

    @Test void requiresNonWebModeBeforeObtainingConsole() {
        env.setProperty("spring.main.web-application-type", "servlet");
        assertThatThrownBy(() -> command().run(arguments())).hasMessage("MAINTENANCE_COMMAND_REQUIRES_NON_WEB_MODE");
        verifyNoInteractions(console, service);
    }
    @Test void requiresSchedulingDisabled() {
        env.setProperty("las.sso.scheduling.enabled", "true");
        assertThatThrownBy(() -> command().run(arguments())).hasMessage("RECOVERY_REQUIRES_SCHEDULING_DISABLED");
        verifyNoInteractions(console, service);
    }
    @Test void requiresInteractiveConsole() {
        var command = new SecurityEpochRecoveryCommand(service, env, () -> null);
        assertThatThrownBy(() -> command.run(arguments())).hasMessage("INTERACTIVE_CONSOLE_REQUIRED");
        verifyNoInteractions(service);
    }
    @Test void cancellationDoesNotCallService() {
        when(console.readLine(anyString())).thenReturn("NO");
        assertThatThrownBy(() -> command().run(arguments())).hasMessage("RECOVERY_CONFIRMATION_REQUIRED");
        verifyNoInteractions(service);
    }
    @Test void rejectsMissingDuplicateBlankOrOversizeOptionsBeforeConfirmation() {
        for (String[] values : new String[][] {{"--reason=TEST"}, {"--expected-epoch=epoch"},
                {"--reason=TEST", "--expected-epoch=epoch", "--expected-epoch=other"},
                {"--reason=", "--expected-epoch=epoch"}, {"--reason=" + "x".repeat(129), "--expected-epoch=epoch"}}) {
            assertThatThrownBy(() -> command().run(new DefaultApplicationArguments(values))).isInstanceOf(SecurityEpochRecoveryException.class);
        }
        verifyNoInteractions(console, service);
    }
    @Test void confirmationPassesExpectedEpochAndPrintsSafeCompletion() {
        when(console.readLine(anyString())).thenReturn("ROTATE-EPOCH-AFTER-RESTORE");
        when(service.rotateAfterRestore("epoch", "TEST")).thenReturn(
                new SecurityEpochRecoveryService.RecoveryResult("epoch", "new-epoch", OffsetDateTime.now()));
        command().run(arguments());
        verify(service).rotateAfterRestore("epoch", "TEST");
        verify(console).printf(anyString(), eq("epoch"), eq("new-epoch"), org.mockito.ArgumentMatchers.any());
    }
    private SecurityEpochRecoveryCommand command() { return new SecurityEpochRecoveryCommand(service, env, () -> console); }
    private DefaultApplicationArguments arguments() { return new DefaultApplicationArguments("--expected-epoch=epoch", "--reason=TEST"); }
}
