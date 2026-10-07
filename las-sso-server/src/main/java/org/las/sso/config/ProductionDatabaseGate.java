package org.las.sso.config;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * One startup snapshot, before servlet listeners open; ordinary lifecycle checks remain
 * request-time.
 */
final class ProductionDatabaseGate {
    private final JdbcClient jdbc;
    private final RegisteredClientRepository clients;
    private final PasswordEncoder encoder;

    ProductionDatabaseGate(
            JdbcClient jdbc, RegisteredClientRepository clients, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.encoder = encoder;
    }

    void validate(boolean web) {
        ProductionConfigurationGate.require(
                jdbc.sql(
                                "SELECT EXISTS (SELECT 1 FROM system_security_state WHERE id = 1 AND length(trim(security_epoch)) > 0)")
                        .query(Boolean.class)
                        .single(),
                "PRODUCTION_SECURITY_STATE_INVALID");
        if (!web) return;
        var administrators =
                jdbc.sql(
                                """
                SELECT u.account_status::text, pc.state::text,
                       pc.temporary_expires_at IS NOT NULL AND pc.temporary_expires_at > CURRENT_TIMESTAMP AS temporary_valid,
                       pc.password_hash IS NOT NULL AND length(trim(pc.password_hash)) > 0 AS has_password,
                       EXISTS (SELECT 1 FROM usernames un WHERE un.user_id = u.id AND un.valid_to IS NULL) AS has_username,
                       EXISTS (SELECT 1 FROM account_deletion_requests d WHERE d.user_id = u.id AND d.status IN ('PENDING', 'HELD')) AS deletion_pending
                  FROM users u LEFT JOIN password_credentials pc ON pc.user_id = u.id
                 WHERE u.is_sso_admin = true
                """)
                        .query(
                                (rs, row) ->
                                        "ACTIVE".equals(rs.getString(1))
                                                && ("ACTIVE".equals(rs.getString(2))
                                                        || "PASSWORD_CHANGE_REQUIRED"
                                                                        .equals(rs.getString(2))
                                                                && rs.getBoolean(3))
                                                && rs.getBoolean(4)
                                                && rs.getBoolean(5)
                                                && !rs.getBoolean(6))
                        .list();
        ProductionConfigurationGate.require(
                administrators.size() == 1 && administrators.getFirst(),
                "PRODUCTION_ADMIN_INITIALIZATION_REQUIRED");
        var registered =
                jdbc.sql(
                                """
                SELECT rc.id, m.status::text, m.require_pkce, m.client_type::text,
                       cardinality(m.resource_audiences) > 0 AS has_audience,
                       b.uri AS backchannel_uri, b.session_required
                  FROM oauth2_registered_client rc
                  LEFT JOIN registered_client_metadata m ON m.registered_client_id = rc.id
                  LEFT JOIN registered_client_backchannel_logout_uris b ON b.registered_client_id = rc.id
                """)
                        .query(
                                (rs, row) ->
                                        new ClientState(
                                                rs.getString(1),
                                                rs.getString(2),
                                                rs.getBoolean(3),
                                                rs.getString(4),
                                                rs.getBoolean(5),
                                                rs.getString(6),
                                                rs.getBoolean(7)))
                        .list();
        for (ClientState state : registered) {
            ProductionConfigurationGate.require(
                    state.status() != null, "PRODUCTION_CLIENT_METADATA_REQUIRED");
            if (!"ACTIVE".equals(state.status())) continue;
            var client = clients.findById(state.id());
            ProductionConfigurationGate.require(client != null, "PRODUCTION_CLIENT_INVALID");
            boolean valid =
                    state.pkce()
                            && client.getClientSettings().isRequireProofKey()
                            && state.hasAudience()
                            && ("FIRST_PARTY".equals(state.type())
                                    || "REVIEWED_PARTNER".equals(state.type())
                                            && client.getClientSettings()
                                                    .isRequireAuthorizationConsent())
                            && client.getClientAuthenticationMethods()
                                    .equals(Set.of(ClientAuthenticationMethod.CLIENT_SECRET_BASIC))
                            && client.getAuthorizationGrantTypes()
                                    .equals(
                                            Set.of(
                                                    AuthorizationGrantType.AUTHORIZATION_CODE,
                                                    AuthorizationGrantType.REFRESH_TOKEN))
                            && !client.getRedirectUris().isEmpty()
                            && client.getScopes().contains("openid")
                            && Set.of("openid", "profile").containsAll(client.getScopes())
                            && client.getRedirectUris().stream()
                                    .allMatch(uri -> ProductionUriPolicy.isHttpsEndpoint(uri, true))
                            && client.getPostLogoutRedirectUris().stream()
                                    .allMatch(uri -> ProductionUriPolicy.isHttpsEndpoint(uri, true))
                            && (state.backchannel() == null
                                    || ProductionUriPolicy.isHttpsEndpoint(
                                                    state.backchannel(), true)
                                            && state.sessionRequired())
                            && client.getClientSecret() != null
                            && (client.getClientSecret()
                                            .matches(
                                                    "\\{argon2id}\\$argon2id\\$v=19\\$m=[0-9]+,t=[0-9]+,p=[0-9]+\\$[A-Za-z0-9+/]+\\$[A-Za-z0-9+/]+")
                                    || client.getClientSecret()
                                            .matches(
                                                    "\\{bcrypt}\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}"));
            ProductionConfigurationGate.require(valid, "PRODUCTION_CLIENT_CONFIGURATION_INVALID");
            for (String secret : List.of("laslog-local-only", "aliencommon-local-only")) {
                ProductionConfigurationGate.require(
                        !encoder.matches(secret, client.getClientSecret()),
                        "PRODUCTION_CLIENT_EXAMPLE_SECRET");
            }
        }
    }

    private record ClientState(
            String id,
            String status,
            boolean pkce,
            String type,
            boolean hasAudience,
            String backchannel,
            boolean sessionRequired) {}
}
