package org.las.sso.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HistoryMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void versionedV15UpgradePreservesHistoryAndBuildsDeleteTriggerAndExpiryIndexes() {
        var data =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = JdbcClient.create(data);
        Flyway.configure()
                .dataSource(data)
                .locations("classpath:db/migration")
                .target("15")
                .load()
                .migrate();
        jdbc.sql(
                        """
                INSERT INTO oauth2_registered_client(id,client_id,client_name,client_authentication_methods,authorization_grant_types,scopes,client_settings,token_settings)
                VALUES ('migration','migration','Migration','client_secret_basic','authorization_code','openid','{}','{}');
                INSERT INTO oauth2_authorization(id,registered_client_id,principal_name,authorization_grant_type,access_token_expires_at)
                VALUES ('migration','migration','opaque-fixture','authorization_code',CURRENT_TIMESTAMP+INTERVAL '1 day');
                INSERT INTO oauth2_id_token_hint_history(token_hash,authorization_id,created_at)
                VALUES (decode(repeat('11',32),'hex'),'migration',CURRENT_TIMESTAMP);
                """)
                .update();
        var upgraded =
                Flyway.configure().dataSource(data).locations("classpath:db/migration").load();
        assertThat(upgraded.migrate().migrationsExecuted).isOne();
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM oauth2_authorization").query(Long.class).single())
                .isOne();
        assertThat(
                        jdbc.sql("SELECT count(*) FROM oauth2_id_token_hint_history")
                                .query(Long.class)
                                .single())
                .isOne();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM pg_indexes WHERE indexname IN ('ix_refresh_history_authorization','ix_user_sessions_expiry','ix_authorization_token_expiry','ix_outbox_live_snapshot')")
                                .query(Long.class)
                                .single())
                .isEqualTo(4);
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
    }
}
