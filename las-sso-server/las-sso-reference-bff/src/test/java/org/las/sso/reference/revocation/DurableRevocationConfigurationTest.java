package org.las.sso.reference.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.las.sso.client.LasSsoClientAutoConfiguration;
import org.las.sso.client.LasSsoRevocationTaskSink;
import org.las.sso.client.LasSsoTokenRevocationQueue;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DurableRevocationConfigurationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test void explicitProfileSuppliesTheOnlySinkAndStartupValidatesSchemaWithoutStartingWorkerWhenDisabled() {
        runner().withPropertyValues("spring.profiles.active=durable-revocation", "reference.revocation.worker-enabled=false",
                "reference.revocation.jdbc-url=" + POSTGRES.getJdbcUrl(), "reference.revocation.username=" + POSTGRES.getUsername(),
                "reference.revocation.password=" + POSTGRES.getPassword(), "reference.revocation.key-id=fixture",
                "reference.revocation.key-base64=" + Base64.getEncoder().encodeToString(new byte[32]))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(LasSsoRevocationTaskSink.class))
                            .hasSize(1).containsKey("referenceRevocationQueue");
                    assertThat(context.getBean(LasSsoRevocationTaskSink.class)).isInstanceOf(JdbcRevocationQueue.class);
                    assertThat(context).hasSingleBean(DataSource.class).hasSingleBean(RevocationWorker.class);
                    assertThat(context).doesNotHaveBean(RevocationSchedulingConfiguration.class);
                    assertThat(context.getBean(JdbcRevocationStore.class).claim(RevocationWorker.LEASE)).isEmpty();
                });
    }

    @Test void defaultProfileKeepsTheExistingInMemorySinkAndRequiresNoDatabaseOrKey() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LasSsoRevocationTaskSink.class)).isInstanceOf(LasSsoTokenRevocationQueue.class);
            assertThat(context).doesNotHaveBean(DataSource.class).doesNotHaveBean(JdbcRevocationQueue.class);
        });
    }

    @Test void enabledProfileWithoutExplicitSettingsFailsRatherThanSilentlyFallingBackToMemory() {
        runner().withPropertyValues("spring.profiles.active=durable-revocation").run(context -> assertThat(context).hasFailed());
    }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(LasSsoClientAutoConfiguration.class))
                .withPropertyValues("las.sso.client.registration-id=sample")
                .withUserConfiguration(Security.class, DurableRevocationConfiguration.class, RevocationSchedulingConfiguration.class)
                .withBean(ClientRegistrationRepository.class, () -> new InMemoryClientRegistrationRepository(
                        JdbcRevocationIntegrationTest.registration("https://issuer.example/revoke", "fixture-only-secret")));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class Security { }
}
