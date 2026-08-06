package org.las.sso.admin;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.las.sso.identity.UuidV7Generator;
import org.las.sso.logout.BackChannelLogoutOutbox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminClientService {

    private static final Set<String> ALLOWED_SCOPES =
            Set.of(OidcScopes.OPENID, OidcScopes.PROFILE);
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_URIS = 10;
    private static final int MAX_AUDIENCES = 10;

    private final JdbcClient jdbcClient;
    private final RegisteredClientRepository registeredClients;
    private final TokenSettings tokenSettings;
    private final PasswordEncoder passwordEncoder;
    private final BackChannelLogoutOutbox backChannelLogout;
    private final UuidV7Generator uuidGenerator;
    private final SecureRandom secureRandom;
    private final Clock clock;

    @Autowired
    public AdminClientService(
            JdbcClient jdbcClient,
            RegisteredClientRepository registeredClients,
            TokenSettings tokenSettings,
            PasswordEncoder passwordEncoder,
            BackChannelLogoutOutbox backChannelLogout,
            UuidV7Generator uuidGenerator) {
        this(jdbcClient, registeredClients, tokenSettings, passwordEncoder, backChannelLogout,
                uuidGenerator, new SecureRandom(), Clock.systemUTC());
    }

    AdminClientService(
            JdbcClient jdbcClient,
            RegisteredClientRepository registeredClients,
            TokenSettings tokenSettings,
            PasswordEncoder passwordEncoder,
            BackChannelLogoutOutbox backChannelLogout,
            UuidV7Generator uuidGenerator,
            SecureRandom secureRandom,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.registeredClients = registeredClients;
        this.tokenSettings = tokenSettings;
        this.passwordEncoder = passwordEncoder;
        this.backChannelLogout = backChannelLogout;
        this.uuidGenerator = uuidGenerator;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ClientPage list(String query, String status, String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, MAX_PAGE_SIZE));
        String normalizedStatus = normalizeStatus(status);
        AdminAuditQueryService.Cursor position = AdminAuditQueryService.decodeCursor(cursor);
        String normalizedQuery = query == null ? "" : query.strip();
        List<ClientSummary> rows = jdbcClient.sql("""
                SELECT rc.id, rc.client_id, rc.client_name, rc.client_id_issued_at,
                       rc.redirect_uris, rc.post_logout_redirect_uris, rc.scopes,
                       m.client_type::text, m.status::text, m.config_version,
                       m.resource_audiences,
                       (SELECT COUNT(*) FROM oauth2_authorization a
                         WHERE a.registered_client_id = rc.id) AS authorization_count
                  FROM oauth2_registered_client rc
                  JOIN registered_client_metadata m ON m.registered_client_id = rc.id
                 WHERE (:query = '' OR rc.client_id ILIKE '%' || :query || '%'
                        OR rc.client_name ILIKE '%' || :query || '%')
                   AND (:status = 'ALL' OR m.status::text = :status)
                   AND (CAST(:cursorTime AS timestamptz) IS NULL
                        OR (rc.client_id_issued_at, rc.id) < (
                            CAST(:cursorTime AS timestamptz), CAST(:cursorId AS varchar)))
                 ORDER BY rc.client_id_issued_at DESC, rc.id DESC
                 LIMIT :limit
                """).param("query", normalizedQuery)
                .param("status", normalizedStatus)
                .param("cursorTime", position == null ? null : position.occurredAt().toString())
                .param("cursorId", position == null ? null : position.id().toString())
                .param("limit", limit + 1)
                .query((rs, rowNum) -> new ClientSummary(
                        rs.getString("id"),
                        rs.getString("client_id"),
                        rs.getString("client_name"),
                        rs.getString("client_type"),
                        rs.getString("status"),
                        rs.getLong("config_version"),
                        splitList(rs.getString("redirect_uris")),
                        splitList(rs.getString("scopes")),
                        rs.getLong("authorization_count"),
                        rs.getObject("client_id_issued_at", OffsetDateTime.class)))
                .list();
        boolean hasMore = rows.size() > limit;
        List<ClientSummary> items = hasMore
                ? List.copyOf(rows.subList(0, limit))
                : List.copyOf(rows);
        String nextCursor = hasMore
                ? AdminAuditQueryService.encodeCursor(
                        items.getLast().issuedAt(), UUID.fromString(items.getLast().id()))
                : null;
        return new ClientPage(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public ClientView detail(String clientId) {
        ClientView client = jdbcClient.sql("""
                SELECT rc.id, rc.client_id, rc.client_name, rc.client_id_issued_at,
                       rc.redirect_uris, rc.post_logout_redirect_uris, rc.scopes,
                       m.client_type::text, m.status::text, m.config_version,
                       m.resource_audiences,
                       (SELECT uri FROM registered_client_backchannel_logout_uris b
                         WHERE b.registered_client_id = rc.id) AS backchannel_uri,
                       (SELECT COUNT(*) FROM oauth2_authorization a
                         WHERE a.registered_client_id = rc.id) AS authorization_count
                  FROM oauth2_registered_client rc
                  JOIN registered_client_metadata m ON m.registered_client_id = rc.id
                 WHERE rc.client_id = :clientId
                """).param("clientId", clientId)
                .query((rs, rowNum) -> new ClientView(
                        rs.getString("id"),
                        rs.getString("client_id"),
                        rs.getString("client_name"),
                        rs.getString("client_type"),
                        rs.getString("status"),
                        rs.getLong("config_version"),
                        splitList(rs.getString("redirect_uris")),
                        splitList(rs.getString("post_logout_redirect_uris")),
                        splitList(rs.getString("scopes")),
                        splitList(rs.getArray("resource_audiences")),
                        rs.getString("backchannel_uri"),
                        "REVIEWED_PARTNER".equals(rs.getString("client_type")),
                        rs.getLong("authorization_count"),
                        rs.getObject("client_id_issued_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new AdminClientException("CLIENT_NOT_FOUND"));
        return client;
    }

    @Transactional
    public CreateResult create(
            CreateRequest request,
            UUID actorUserId,
            String actorSid,
            String reason,
            UUID requestId) {
        OffsetDateTime now = now();
        String clientId = request.clientId() == null || request.clientId().isBlank()
                ? generateClientId()
                : request.clientId().strip();
        validateClientId(clientId);
        if (registeredClients.findByClientId(clientId) != null) {
            throw new AdminClientException("CLIENT_ID_CONFLICT");
        }
        List<String> redirectUris = validatedUris(request.redirectUris(), true);
        List<String> postLogoutUris = validatedUris(request.postLogoutRedirectUris(), false);
        List<String> scopes = validatedScopes(request.scopes());
        List<String> audiences = validatedAudiences(request.resourceAudiences());
        String backchannelUri = validatedBackchannelUri(request.backchannelLogoutUri());
        boolean partner = "REVIEWED_PARTNER".equals(request.clientType());
        String plainSecret = generateClientSecret();
        RegisteredClient client = RegisteredClient.withId(uuidGenerator.generate().toString())
                .clientId(clientId)
                .clientName(request.clientName())
                .clientSecret(passwordEncoder.encode(plainSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> redirectUris.forEach(uris::add))
                .postLogoutRedirectUris(uris -> postLogoutUris.forEach(uris::add))
                .scopes(scope -> scopes.forEach(scope::add))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(partner)
                        .build())
                .tokenSettings(tokenSettings)
                .build();
        try {
            registeredClients.save(client);
        } catch (DuplicateKeyException exception) {
            throw new AdminClientException("CLIENT_ID_CONFLICT");
        }
        jdbcClient.sql("""
                INSERT INTO registered_client_metadata
                    (registered_client_id, client_type, status, config_version,
                     resource_audiences, require_pkce, updated_at)
                VALUES (:id, CAST(:clientType AS registered_client_type), 'ACTIVE', 1,
                        :audiences::text[], true, :now)
                """).param("id", client.getId())
                .param("clientType", partner ? "REVIEWED_PARTNER" : "FIRST_PARTY")
                .param("audiences", audiences.toArray(new String[0]))
                .param("now", now).update();
        if (backchannelUri != null) {
            jdbcClient.sql("""
                    INSERT INTO registered_client_backchannel_logout_uris
                        (registered_client_id, uri, session_required)
                    VALUES (:id, :uri, true)
                    """).param("id", client.getId()).param("uri", backchannelUri).update();
        }
        insertAudit(actorUserId, actorSid, clientId, "ADMIN_CLIENT_CREATE", reason, requestId, now);
        return new CreateResult(clientId, plainSecret, now, null, 1);
    }

    @Transactional
    public PatchResult patch(
            String clientId,
            PatchRequest request,
            UUID actorUserId,
            String actorSid,
            String reason,
            UUID requestId) {
        OffsetDateTime now = now();
        RegisteredClient current = requireClient(clientId);
        String currentType = clientType(clientId);
        boolean partner = "REVIEWED_PARTNER".equals(currentType);

        List<String> newRedirectUris = validatedUris(request.redirectUris(), true);
        List<String> newPostLogoutUris = validatedUris(request.postLogoutRedirectUris(), false);
        List<String> newScopes = validatedScopes(request.scopes());
        List<String> newAudiences = validatedAudiences(request.resourceAudiences());
        String newBackchannelUri = validatedBackchannelUri(request.backchannelLogoutUri());
        boolean newPartner = "REVIEWED_PARTNER".equals(request.clientType());

        boolean scopesShrunk = !newScopes.containsAll(current.getScopes());
        boolean audiencesShrunk = !newAudiences.containsAll(currentAudiences(clientId));
        boolean typeChanged = newPartner != partner;
        boolean securityRelevant = scopesShrunk || audiencesShrunk || typeChanged
                || !newRedirectUris.equals(List.copyOf(current.getRedirectUris()))
                || !newPostLogoutUris.equals(List.copyOf(current.getPostLogoutRedirectUris()))
                || !java.util.Objects.equals(
                        newBackchannelUri, currentBackchannelUri(clientId));
        boolean revoke = scopesShrunk || audiencesShrunk || typeChanged;

        RegisteredClient updated = RegisteredClient.from(current)
                .clientName(request.clientName())
                .redirectUris(uris -> {
                    uris.clear();
                    newRedirectUris.forEach(uris::add);
                })
                .postLogoutRedirectUris(uris -> {
                    uris.clear();
                    newPostLogoutUris.forEach(uris::add);
                })
                .scopes(scope -> {
                    scope.clear();
                    newScopes.forEach(scope::add);
                })
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(newPartner)
                        .build())
                .build();
        registeredClients.save(updated);

        long configVersion = configVersion(clientId);
        if (securityRelevant) {
            configVersion = configVersion + 1;
            jdbcClient.sql("""
                    UPDATE registered_client_metadata
                       SET config_version = config_version + 1,
                           client_type = CAST(:clientType AS registered_client_type),
                           resource_audiences = :audiences::text[],
                           require_pkce = true,
                           updated_at = :now
                     WHERE registered_client_id = :id
                    """).param("clientType", newPartner ? "REVIEWED_PARTNER" : "FIRST_PARTY")
                    .param("audiences", newAudiences.toArray(new String[0]))
                    .param("now", now).param("id", current.getId()).update();
        } else {
            jdbcClient.sql("""
                    UPDATE registered_client_metadata
                       SET client_type = CAST(:clientType AS registered_client_type),
                           resource_audiences = :audiences::text[],
                           require_pkce = true,
                           updated_at = :now
                     WHERE registered_client_id = :id
                    """).param("clientType", newPartner ? "REVIEWED_PARTNER" : "FIRST_PARTY")
                    .param("audiences", newAudiences.toArray(new String[0]))
                    .param("now", now).param("id", current.getId()).update();
        }
        if (newBackchannelUri != null) {
            jdbcClient.sql("""
                    INSERT INTO registered_client_backchannel_logout_uris
                        (registered_client_id, uri, session_required)
                    VALUES (:id, :uri, true)
                    ON CONFLICT (registered_client_id) DO UPDATE SET
                        uri = EXCLUDED.uri, session_required = EXCLUDED.session_required
                    """).param("id", current.getId()).param("uri", newBackchannelUri).update();
        } else {
            jdbcClient.sql("""
                    DELETE FROM registered_client_backchannel_logout_uris
                     WHERE registered_client_id = :id
                    """).param("id", current.getId()).update();
        }

        int revoked = 0;
        if (revoke) {
            revoked = revokeClientAuthorizations(
                    current.getId(), current.getClientId(), "CLIENT_CONFIG_REVOKED", now);
        }
        insertAudit(actorUserId, actorSid, clientId, "ADMIN_CLIENT_UPDATE", reason, requestId, now);
        return new PatchResult(revoke, revoked, configVersion);
    }

    @Transactional
    public long disable(
            String clientId, UUID actorUserId, String actorSid, String reason, UUID requestId) {
        OffsetDateTime now = now();
        RegisteredClient client = requireClient(clientId);
        jdbcClient.sql("""
                UPDATE registered_client_metadata
                   SET status = 'DISABLED', config_version = config_version + 1, updated_at = :now
                 WHERE registered_client_id = :id
                """).param("now", now).param("id", client.getId()).update();
        revokeClientAuthorizations(
                client.getId(), client.getClientId(), "CLIENT_DISABLED", now);
        securityEvent("CLIENT_DISABLED_BY_ADMIN", "HIGH", clientId, requestId, now);
        insertAudit(actorUserId, actorSid, clientId, "ADMIN_CLIENT_DISABLE", reason, requestId, now);
        return configVersion(clientId);
    }

    @Transactional
    public long enable(
            String clientId, UUID actorUserId, String actorSid, String reason, UUID requestId) {
        OffsetDateTime now = now();
        RegisteredClient client = requireClient(clientId);
        jdbcClient.sql("""
                UPDATE registered_client_metadata
                   SET status = 'ACTIVE', config_version = config_version + 1, updated_at = :now
                 WHERE registered_client_id = :id
                """).param("now", now).param("id", client.getId()).update();
        securityEvent("CLIENT_ENABLED_BY_ADMIN", "INFO", clientId, requestId, now);
        insertAudit(actorUserId, actorSid, clientId, "ADMIN_CLIENT_ENABLE", reason, requestId, now);
        return configVersion(clientId);
    }

    @Transactional
    public RotationResult rotateSecret(
            String clientId, UUID actorUserId, String actorSid, String reason, UUID requestId) {
        OffsetDateTime now = now();
        RegisteredClient client = requireClient(clientId);
        String plainSecret = generateClientSecret();
        RegisteredClient rotated = RegisteredClient.from(client)
                .clientSecret(passwordEncoder.encode(plainSecret))
                .build();
        registeredClients.save(rotated);
        jdbcClient.sql("""
                UPDATE registered_client_metadata
                   SET config_version = config_version + 1, updated_at = :now
                 WHERE registered_client_id = :id
                """).param("now", now).param("id", client.getId()).update();
        revokeClientAuthorizations(
                client.getId(), client.getClientId(), "CLIENT_SECRET_ROTATED", now);
        securityEvent("CLIENT_SECRET_ROTATED", "HIGH", clientId, requestId, now);
        insertAudit(actorUserId, actorSid, clientId, "ADMIN_CLIENT_SECRET_ROTATE", reason,
                requestId, now);
        return new RotationResult(plainSecret, now, null, configVersion(clientId));
    }

    private int revokeClientAuthorizations(
            String registeredClientId, String clientId, String reason, OffsetDateTime now) {
        List<UUID> principals = jdbcClient.sql("""
                SELECT DISTINCT CAST(principal_name AS uuid)
                  FROM oauth2_authorization
                 WHERE registered_client_id = :rcId
                """).param("rcId", registeredClientId)
                .query(UUID.class).list();
        for (UUID userId : principals) {
            backChannelLogout.enqueueForClient(userId, clientId, reason, now);
        }
        int deleted = jdbcClient.sql("""
                DELETE FROM oauth2_authorization WHERE registered_client_id = :rcId
                """).param("rcId", registeredClientId).update();
        jdbcClient.sql("""
                DELETE FROM oauth2_authorization_consent WHERE registered_client_id = :rcId
                """).param("rcId", registeredClientId).update();
        return deleted;
    }

    private RegisteredClient requireClient(String clientId) {
        RegisteredClient client = registeredClients.findByClientId(clientId);
        if (client == null) {
            throw new AdminClientException("CLIENT_NOT_FOUND");
        }
        return client;
    }

    RegisteredClient findByClientId(String clientId) {
        return requireClient(clientId);
    }

    private String clientType(String clientId) {
        return jdbcClient.sql("""
                SELECT client_type::text FROM registered_client_metadata
                 WHERE registered_client_id = :id
                """).param("id", requireClient(clientId).getId())
                .query(String.class).optional().orElse("FIRST_PARTY");
    }

    private long configVersion(String clientId) {
        return jdbcClient.sql("""
                SELECT config_version FROM registered_client_metadata
                 WHERE registered_client_id = :id
                """).param("id", requireClient(clientId).getId())
                .query(Long.class).optional().orElse(1L);
    }

    private List<String> currentAudiences(String clientId) {
        return jdbcClient.sql("""
                SELECT resource_audiences FROM registered_client_metadata
                 WHERE registered_client_id = :id
                """).param("id", requireClient(clientId).getId())
                .query((rs, rowNum) -> splitList(rs.getArray("resource_audiences")))
                .optional().orElse(List.of());
    }

    private String currentBackchannelUri(String clientId) {
        return jdbcClient.sql("""
                SELECT uri FROM registered_client_backchannel_logout_uris
                 WHERE registered_client_id = :id
                """).param("id", requireClient(clientId).getId())
                .query(String.class).optional().orElse(null);
    }

    private void insertAudit(
            UUID actorUserId,
            String actorSid,
            String clientId,
            String action,
            String reason,
            UUID requestId,
            OffsetDateTime now) {
        UUID actorSessionId = actorSid == null ? null : jdbcClient.sql("""
                SELECT id FROM user_sessions WHERE sid = :sid AND user_id = :actorUserId
                """).param("sid", actorSid).param("actorUserId", actorUserId)
                .query(UUID.class).optional().orElse(null);
        jdbcClient.sql("""
                INSERT INTO admin_audit_events
                    (id, occurred_at, actor_user_id, actor_session_id, action,
                     target_type, target_id, request_id, result, reason)
                VALUES (:id, :now, :actorUserId, :actorSessionId, :action,
                        'CLIENT', :clientId, :requestId, 'SUCCESS', :reason)
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("actorUserId", actorUserId).param("actorSessionId", actorSessionId)
                .param("action", action).param("clientId", clientId)
                .param("requestId", requestId).param("reason", reason).update();
    }

    private void securityEvent(
            String type, String severity, String clientId, UUID requestId, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO security_events
                    (id, occurred_at, event_type, severity, request_id, client_id, details_json)
                VALUES (:id, :now, :type, :severity, :requestId, :clientId, '{}'::jsonb)
                """).param("id", uuidGenerator.generate()).param("now", now)
                .param("type", type).param("severity", severity)
                .param("requestId", requestId).param("clientId", clientId).update();
    }

    private static List<String> validatedUris(List<String> uris, boolean required) {
        if (uris == null || uris.isEmpty()) {
            if (required) {
                throw new AdminClientException("REDIRECT_URI_REQUIRED");
            }
            return List.of();
        }
        if (uris.size() > MAX_URIS) {
            throw new AdminClientException("TOO_MANY_URIS");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : uris) {
            String uri = raw.strip();
            validateUri(uri);
            seen.add(uri);
        }
        return List.copyOf(seen);
    }

    private static void validateUri(String uri) {
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException exception) {
            throw new AdminClientException("INVALID_REDIRECT_URI");
        }
        if (parsed.getFragment() != null || parsed.getUserInfo() != null
                || uri.contains("*") || uri.contains("#")
                || parsed.getHost() == null) {
            throw new AdminClientException("INVALID_REDIRECT_URI");
        }
        String scheme = parsed.getScheme();
        if (scheme == null) {
            throw new AdminClientException("INVALID_REDIRECT_URI");
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if ("http".equalsIgnoreCase(scheme)
                && ("localhost".equalsIgnoreCase(parsed.getHost())
                        || "127.0.0.1".equals(parsed.getHost()))) {
            return;
        }
        throw new AdminClientException("INVALID_REDIRECT_URI");
    }

    private static String validatedBackchannelUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String stripped = uri.strip();
        validateUri(stripped);
        return stripped;
    }

    private static List<String> validatedScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new AdminClientException("SCOPE_REQUIRED");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String scope : scopes) {
            String stripped = scope.strip();
            if (!ALLOWED_SCOPES.contains(stripped)) {
                throw new AdminClientException("INVALID_SCOPE");
            }
            seen.add(stripped);
        }
        if (!seen.contains(OidcScopes.OPENID)) {
            throw new AdminClientException("INVALID_SCOPE");
        }
        return List.copyOf(seen);
    }

    private static List<String> validatedAudiences(List<String> audiences) {
        if (audiences == null || audiences.isEmpty()) {
            throw new AdminClientException("AUDIENCE_REQUIRED");
        }
        if (audiences.size() > MAX_AUDIENCES) {
            throw new AdminClientException("TOO_MANY_AUDIENCES");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String audience : audiences) {
            String stripped = audience.strip();
            if (stripped.isBlank() || stripped.length() > 200) {
                throw new AdminClientException("INVALID_AUDIENCE");
            }
            seen.add(stripped);
        }
        return List.copyOf(seen);
    }

    private static void validateClientId(String clientId) {
        if (!clientId.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,99}")) {
            throw new AdminClientException("INVALID_CLIENT_ID");
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "ACTIVE";
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "DISABLED", "ALL").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported client status filter");
        }
        return normalized;
    }

    private String generateClientId() {
        byte[] bytes = new byte[6];
        secureRandom.nextBytes(bytes);
        return "app-" + java.util.HexFormat.of().formatHex(bytes);
    }

    private String generateClientSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "las_client_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static List<String> splitList(String commaJoined) {
        if (commaJoined == null || commaJoined.isBlank()) {
            return List.of();
        }
        return List.of(commaJoined.split(",", -1));
    }

    private static List<String> splitList(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object[] values = (Object[]) array.getArray();
            List<String> result = new ArrayList<>(values.length);
            for (Object value : values) {
                result.add(String.valueOf(value));
            }
            return List.copyOf(result);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Failed to read resource audiences", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record CreateRequest(
            String clientId,
            String clientName,
            String clientType,
            List<String> redirectUris,
            List<String> postLogoutRedirectUris,
            List<String> scopes,
            List<String> resourceAudiences,
            String backchannelLogoutUri) {
    }

    public record PatchRequest(
            String clientName,
            String clientType,
            List<String> redirectUris,
            List<String> postLogoutRedirectUris,
            List<String> scopes,
            List<String> resourceAudiences,
            String backchannelLogoutUri) {
    }

    public record ClientSummary(
            String id,
            String clientId,
            String clientName,
            String clientType,
            String status,
            long configVersion,
            List<String> redirectUris,
            List<String> scopes,
            long authorizationCount,
            OffsetDateTime issuedAt) {
    }

    public record ClientPage(List<ClientSummary> items, String nextCursor) {
        public ClientPage {
            items = List.copyOf(items);
        }
    }

    public record ClientView(
            String id,
            String clientId,
            String clientName,
            String clientType,
            String status,
            long configVersion,
            List<String> redirectUris,
            List<String> postLogoutRedirectUris,
            List<String> scopes,
            List<String> resourceAudiences,
            String backchannelLogoutUri,
            boolean requireConsent,
            long authorizationCount,
            OffsetDateTime issuedAt) {
    }

    public record CreateResult(
            String clientId,
            String clientSecret,
            OffsetDateTime validFrom,
            OffsetDateTime expiresAt,
            long configVersion) {
    }

    public record PatchResult(
            boolean revocationTriggered,
            int revokedAuthorizationCount,
            long configVersion) {
    }

    public record RotationResult(
            String clientSecret,
            OffsetDateTime validFrom,
            OffsetDateTime expiresAt,
            long configVersion) {
    }
}
