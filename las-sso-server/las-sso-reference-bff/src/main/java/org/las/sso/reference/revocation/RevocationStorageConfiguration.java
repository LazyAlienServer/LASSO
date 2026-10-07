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

@Configuration(proxyBeanMethods = false)
@Profile("durable-revocation")
@EnableConfigurationProperties(RevocationProperties.class)
class RevocationStorageConfiguration {
    @Bean(name = "referenceRevocationDataSource")
    DataSource dataSource(RevocationProperties properties) {
        return new DriverManagerDataSource(
                properties.jdbcUrl(), properties.username(), properties.password());
    }

    @Bean
    JdbcRevocationStore referenceRevocationStore(
            @Qualifier("referenceRevocationDataSource") DataSource dataSource,
            RevocationTokenCipher cipher) {
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("reference_revocation")
                .locations("classpath:revocation-db")
                .load()
                .migrate();
        try {
            return new JdbcRevocationStore(
                    JdbcClient.create(dataSource),
                    new DataSourceTransactionManager(dataSource),
                    cipher);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Revocation storage or active encryption key unavailable");
        }
    }

    @Bean
    RevocationTokenCipher referenceRevocationCipher(RevocationProperties properties) {
        return new RevocationTokenCipher(properties.keyId(), properties.keyBase64());
    }
}
