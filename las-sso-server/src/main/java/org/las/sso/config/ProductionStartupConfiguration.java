package org.las.sso.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@Configuration(proxyBeanMethods = false)
class ProductionStartupConfiguration {
    @Bean
    static BeanFactoryPostProcessor productionConfigurationGate(Environment environment) {
        return beanFactory -> ProductionConfigurationGate.validate(environment);
    }

    @Bean
    @Profile("production")
    @Lazy(false)
    @DependsOn("flywayInitializer")
    ProductionDatabaseGate productionDatabaseGate(
            Environment environment,
            JdbcClient jdbc,
            RegisteredClientRepository clients,
            PasswordEncoder encoder,
            Flyway flyway) {
        ProductionDatabaseGate gate = new ProductionDatabaseGate(jdbc, clients, encoder);
        try {
            var migrations = flyway.info().all();
            ProductionConfigurationGate.require(
                    migrations.length > 0
                            && java.util.Arrays.stream(migrations)
                                    .allMatch(
                                            migration ->
                                                    migration.getState() == MigrationState.SUCCESS
                                                            && migration.isChecksumMatching()),
                    "PRODUCTION_SCHEMA_INCOMPATIBLE");
            gate.validate(
                    !"none"
                            .equalsIgnoreCase(
                                    environment.getProperty(
                                            "spring.main.web-application-type", "servlet")));
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("PRODUCTION_"))
                throw exception;
            throw new IllegalStateException("PRODUCTION_DATABASE_CONFIGURATION_INVALID");
        } catch (RuntimeException exception) {
            // Repository/deserializer failures may include stored client values; do not expose
            // them.
            throw new IllegalStateException("PRODUCTION_DATABASE_CONFIGURATION_INVALID");
        }
        return gate;
    }
}
