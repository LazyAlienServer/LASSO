package org.las.sso.maintenance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class DatabaseMigrationCommandTest {
    @Test
    void offlineCompletionDoesNotRequireAdministratorOrConsole() {
        var flyway = mock(Flyway.class);
        var validation =
                new ValidateResult(
                        "fixture",
                        "fixture",
                        null,
                        true,
                        16,
                        java.util.List.of(),
                        java.util.List.of());
        when(flyway.validateWithResult()).thenReturn(validation);
        var info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(new MigrationInfo[0]);
        assertThatCode(
                        () ->
                                new DatabaseMigrationCommand(offline(), flyway)
                                        .run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
        when(flyway.validateWithResult())
                .thenReturn(
                        new ValidateResult(
                                "fixture",
                                "fixture",
                                null,
                                false,
                                16,
                                java.util.List.of(),
                                java.util.List.of()));
        assertThatThrownBy(
                        () ->
                                new DatabaseMigrationCommand(offline(), flyway)
                                        .run(new DefaultApplicationArguments()))
                .hasMessage("DATABASE_MIGRATION_NOT_COMPLETE");
        when(flyway.validateWithResult()).thenReturn(validation);
        when(info.pending()).thenReturn(new MigrationInfo[] {mock(MigrationInfo.class)});
        assertThatThrownBy(
                        () ->
                                new DatabaseMigrationCommand(offline(), flyway)
                                        .run(new DefaultApplicationArguments()))
                .hasMessage("DATABASE_MIGRATION_NOT_COMPLETE");
    }

    @Test
    void webOrScheduledExecutionIsRefusedBeforeMigrationInspection() {
        var flyway = mock(Flyway.class);
        assertThatThrownBy(
                        () ->
                                new DatabaseMigrationCommand(
                                                offline()
                                                        .withProperty(
                                                                "spring.main.web-application-type",
                                                                "servlet"),
                                                flyway)
                                        .run(new DefaultApplicationArguments()))
                .hasMessage("DATABASE_MIGRATION_REQUIRES_OFFLINE_MODE");
        assertThatThrownBy(
                        () ->
                                new DatabaseMigrationCommand(
                                                offline()
                                                        .withProperty(
                                                                "las.sso.scheduling.enabled",
                                                                "true"),
                                                flyway)
                                        .run(new DefaultApplicationArguments()))
                .hasMessage("DATABASE_MIGRATION_REQUIRES_OFFLINE_MODE");
    }

    private MockEnvironment offline() {
        return new MockEnvironment()
                .withProperty("spring.main.web-application-type", "none")
                .withProperty("las.sso.scheduling.enabled", "false")
                .withProperty("spring.session.jdbc.cleanup-cron", "-");
    }
}
