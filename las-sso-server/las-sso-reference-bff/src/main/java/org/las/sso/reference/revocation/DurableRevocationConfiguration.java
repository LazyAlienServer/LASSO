package org.las.sso.reference.revocation;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration(proxyBeanMethods = false)
@Profile("durable-revocation")
@EnableConfigurationProperties(RevocationProperties.class)
class DurableRevocationConfiguration {
    @Bean(name = "referenceRevocationDataSource")
    DataSource dataSource(RevocationProperties properties) {
        return new DriverManagerDataSource(
                properties.jdbcUrl(), properties.username(), properties.password());
    }

    @Bean
    JdbcRevocationStore referenceRevocationStore(
            @Qualifier("referenceRevocationDataSource") DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("reference_revocation")
                .locations("classpath:revocation-db")
                .load()
                .migrate();
        return new JdbcRevocationStore(
                JdbcClient.create(dataSource), new DataSourceTransactionManager(dataSource));
    }

    @Bean
    RevocationTokenCipher referenceRevocationCipher(RevocationProperties properties) {
        return new RevocationTokenCipher(properties.keyId(), properties.keyBase64());
    }

    @Bean
    JdbcRevocationQueue referenceRevocationQueue(
            JdbcRevocationStore store,
            RevocationTokenCipher cipher,
            RevocationProperties properties) {
        return new JdbcRevocationQueue(store, cipher, properties.allowLoopbackHttp());
    }

    @Bean
    RevocationWorker referenceRevocationWorker(
            JdbcRevocationStore store,
            RevocationTokenCipher cipher,
            ClientRegistrationRepository registrations,
            RevocationProperties properties) {
        return new RevocationWorker(store, cipher, registrations, properties.allowLoopbackHttp());
    }
}
