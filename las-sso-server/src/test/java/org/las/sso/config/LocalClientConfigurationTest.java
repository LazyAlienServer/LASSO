package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "las.sso.scheduling.enabled=false",
        "spring.session.jdbc.cleanup-cron=-"
})
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class LocalClientConfigurationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired
    RegisteredClientRepository clients;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void localLaslogClientUsesDocumentedCredentialAndRedirectUri() {
        var client = clients.findByClientId("laslog-bff");

        assertThat(client).isNotNull();
        assertThat(passwordEncoder.matches("laslog-local-only", client.getClientSecret())).isTrue();
        assertThat(client.getRedirectUris()).containsExactlyInAnyOrder(
                "http://localhost:8088/login/oauth2/code/las-sso",
                "http://127.0.0.1:8088/login/oauth2/code/las-sso");
    }
}
