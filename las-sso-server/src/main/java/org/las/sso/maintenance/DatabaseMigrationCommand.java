package org.las.sso.maintenance;

import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Boot's Flyway initializer performs the only migration; this runner validates offline completion.
 */
@Component
@Lazy(false)
@ConditionalOnProperty(name = "las.sso.maintenance.command", havingValue = "database-migrate")
public final class DatabaseMigrationCommand implements ApplicationRunner {
    private final Environment environment;
    private final Flyway flyway;

    public DatabaseMigrationCommand(Environment environment, Flyway flyway) {
        this.environment = environment;
        this.flyway = flyway;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!"none"
                        .equalsIgnoreCase(
                                environment.getProperty("spring.main.web-application-type", ""))
                || !"false"
                        .equalsIgnoreCase(environment.getProperty("las.sso.scheduling.enabled", ""))
                || !"-".equals(environment.getProperty("spring.session.jdbc.cleanup-cron", ""))) {
            throw new IllegalStateException("DATABASE_MIGRATION_REQUIRES_OFFLINE_MODE");
        }
        if (!flyway.validateWithResult().validationSuccessful
                || flyway.info().pending().length != 0) {
            throw new IllegalStateException("DATABASE_MIGRATION_NOT_COMPLETE");
        }
        System.out.println(
                "Database migrations validated; no HTTP listener or application jobs started.");
    }
}
