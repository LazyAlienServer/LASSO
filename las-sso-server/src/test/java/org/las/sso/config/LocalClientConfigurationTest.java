package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class LocalClientConfigurationTest {

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
