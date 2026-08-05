package org.las.sso.config;

import org.las.sso.identity.UuidV7Generator;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalClientConfiguration {

    @Bean
    ApplicationRunner seedLocalClients(
            RegisteredClientRepository clients,
            TokenSettings firstPartyTokenSettings,
            PasswordEncoder passwordEncoder,
            UuidV7Generator uuidGenerator,
            JdbcClient jdbcClient) {
        return arguments -> {
            ensureClient(
                    clients,
                    firstPartyTokenSettings,
                    passwordEncoder,
                    uuidGenerator,
                    jdbcClient,
                    "laslog-bff",
                    "LASLOG local BFF",
                    "laslog-local-only",
                    "http://localhost:8088/login/oauth2/code/las-sso",
                    "http://127.0.0.1:8088/login/oauth2/code/las-sso",
                    "http://localhost:8088/logged-out",
                    "http://127.0.0.1:8088/logged-out",
                    "http://localhost:8088/internal/oidc/backchannel-logout",
                    "urn:las:resource:laslog");
            ensureClient(
                    clients,
                    firstPartyTokenSettings,
                    passwordEncoder,
                    uuidGenerator,
                    jdbcClient,
                    "aliencommon-bff",
                    "AlienCommon local BFF",
                    "aliencommon-local-only",
                    "http://localhost:8000/auth/oidc/callback",
                    "http://127.0.0.1:8000/auth/oidc/callback",
                    "http://localhost:8000/logged-out",
                    "http://127.0.0.1:8000/logged-out",
                    "http://localhost:8000/internal/oidc/backchannel-logout",
                    "urn:las:resource:aliencommon");
        };
    }

    private static void ensureClient(
            RegisteredClientRepository clients,
            TokenSettings tokenSettings,
            PasswordEncoder passwordEncoder,
            UuidV7Generator uuidGenerator,
            JdbcClient jdbcClient,
            String clientId,
            String clientName,
            String clientSecret,
            String redirectUri,
            String alternateRedirectUri,
            String postLogoutRedirectUri,
            String alternatePostLogoutRedirectUri,
            String backchannelLogoutUri,
            String resourceAudience) {
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null) {
            client = RegisteredClient.withId(uuidGenerator.generate().toString())
                    .clientId(clientId)
                    .clientName(clientName)
                    .clientSecret(passwordEncoder.encode(clientSecret))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUri(redirectUri)
                    .redirectUri(alternateRedirectUri)
                    .postLogoutRedirectUri(postLogoutRedirectUri)
                    .postLogoutRedirectUri(alternatePostLogoutRedirectUri)
                    .scope(OidcScopes.OPENID)
                    .scope(OidcScopes.PROFILE)
                    .clientSettings(ClientSettings.builder()
                            .requireProofKey(true)
                            .requireAuthorizationConsent(false)
                            .build())
                    .tokenSettings(tokenSettings)
                    .build();
            clients.save(client);
        } else if (!passwordEncoder.matches(clientSecret, client.getClientSecret())
                || !client.getRedirectUris().contains(redirectUri)
                || !client.getRedirectUris().contains(alternateRedirectUri)
                || !client.getPostLogoutRedirectUris().contains(postLogoutRedirectUri)
                || !client.getPostLogoutRedirectUris().contains(alternatePostLogoutRedirectUri)) {
            var builder = RegisteredClient.from(client)
                    .redirectUri(redirectUri)
                    .redirectUri(alternateRedirectUri)
                    .postLogoutRedirectUri(postLogoutRedirectUri)
                    .postLogoutRedirectUri(alternatePostLogoutRedirectUri);
            if (!passwordEncoder.matches(clientSecret, client.getClientSecret())) {
                builder.clientSecret(passwordEncoder.encode(clientSecret));
            }
            client = builder.build();
            clients.save(client);
        }

        jdbcClient.sql("""
                INSERT INTO registered_client_metadata
                    (registered_client_id, client_type, status, config_version,
                     resource_audiences, require_pkce, updated_at)
                VALUES (:id, 'FIRST_PARTY', 'ACTIVE', 1, ARRAY[:audience]::text[], true, CURRENT_TIMESTAMP)
                ON CONFLICT (registered_client_id) DO UPDATE SET
                    resource_audiences = EXCLUDED.resource_audiences,
                    require_pkce = EXCLUDED.require_pkce,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("id", client.getId())
                .param("audience", resourceAudience)
                .update();
        jdbcClient.sql("""
                INSERT INTO registered_client_backchannel_logout_uris
                    (registered_client_id, uri, session_required)
                VALUES (:id, :uri, true)
                ON CONFLICT (registered_client_id) DO UPDATE SET
                    uri = EXCLUDED.uri,
                    session_required = EXCLUDED.session_required
                """)
                .param("id", client.getId())
                .param("uri", backchannelLogoutUri)
                .update();
    }
}
