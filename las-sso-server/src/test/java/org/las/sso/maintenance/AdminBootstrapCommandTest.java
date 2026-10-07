package org.las.sso.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.Console;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

/**
 * Console acquisition is a fixture; real JVM/shell hidden-input and process exit are separate
 * acceptance.
 */
class AdminBootstrapCommandTest {

    @Test
    void deletionRefusalExplainsOwnerCancellationPropagatesFailureAndWipesBothPasswordBuffers() {
        var service = mock(AdminBootstrapService.class);
        UUID userId = UUID.randomUUID();
        String secret = "command-password-secret-canary";
        doThrow(new AdminBootstrapException("DELETION_CANCELLATION_REQUIRED"))
                .when(service)
                .promoteExistingUser(userId, secret);
        var console = new ConsoleFixture(secret);
        var command =
                new AdminBootstrapCommand(
                        service,
                        new MockEnvironment()
                                .withProperty("spring.main.web-application-type", "none"),
                        () -> console.console);

        assertThatThrownBy(
                        () -> command.run(new DefaultApplicationArguments("--user-id=" + userId)))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("DELETION_CANCELLATION_REQUIRED");

        assertThat(console.output.toString())
                .contains(
                        "promotion refused",
                        "DELETION_CANCELLATION_REQUIRED",
                        "account owner must cancel",
                        "then retry")
                .doesNotContain("bootstrap completed", secret);
        assertThat(console.first).containsOnly('\0');
        assertThat(console.second).containsOnly('\0');
    }

    @Test
    void unrelatedFailureIsNotReportedAsDeletionCancellationAndStillWipesPasswords() {
        var service = mock(AdminBootstrapService.class);
        UUID userId = UUID.randomUUID();
        String secret = "command-password-secret-canary";
        doThrow(new AdminBootstrapException("ADMIN_ALREADY_EXISTS"))
                .when(service)
                .promoteExistingUser(userId, secret);
        var console = new ConsoleFixture(secret);
        var command =
                new AdminBootstrapCommand(
                        service,
                        new MockEnvironment()
                                .withProperty("spring.main.web-application-type", "none"),
                        () -> console.console);

        assertThatThrownBy(
                        () -> command.run(new DefaultApplicationArguments("--user-id=" + userId)))
                .isInstanceOf(AdminBootstrapException.class)
                .extracting("code")
                .isEqualTo("ADMIN_ALREADY_EXISTS");

        assertThat(console.output.toString()).doesNotContain("completed", "cancel", secret);
        assertThat(console.first).containsOnly('\0');
        assertThat(console.second).containsOnly('\0');
    }

    @Test
    void successfulPromotionStillPrintsSafeResultAndWipesPasswords() {
        var service = mock(AdminBootstrapService.class);
        UUID userId = UUID.randomUUID();
        String secret = "command-password-secret-canary";
        var result =
                new AdminBootstrapService.BootstrapResult(
                        userId,
                        "CommandOwner",
                        OffsetDateTime.now().plusHours(24),
                        UUID.randomUUID());
        when(service.promoteExistingUser(userId, secret)).thenReturn(result);
        var console = new ConsoleFixture(secret);
        var command =
                new AdminBootstrapCommand(
                        service,
                        new MockEnvironment()
                                .withProperty("spring.main.web-application-type", "none"),
                        () -> console.console);

        command.run(new DefaultApplicationArguments("--user-id=" + userId));

        assertThat(console.output.toString())
                .contains(
                        "bootstrap completed", userId.toString(), result.auditEventId().toString())
                .doesNotContain("refused", secret);
        assertThat(console.first).containsOnly('\0');
        assertThat(console.second).containsOnly('\0');
    }

    private static final class ConsoleFixture {
        final char[] first;
        final char[] second;
        final StringBuilder output = new StringBuilder();
        final Console console;

        ConsoleFixture(String password) {
            first = password.toCharArray();
            second = password.toCharArray();
            var reads = new AtomicInteger();
            console =
                    mock(
                            Console.class,
                            invocation -> {
                                if (invocation.getMethod().getName().equals("readPassword")) {
                                    return reads.getAndIncrement() == 0 ? first : second;
                                }
                                if (invocation.getMethod().getName().equals("printf")) {
                                    Object[] args = invocation.getRawArguments();
                                    output.append(
                                            String.format((String) args[0], (Object[]) args[1]));
                                    return invocation.getMock();
                                }
                                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                            });
        }
    }
}
