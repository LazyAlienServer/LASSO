package org.las.sso.oidc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.las.sso.identity.UuidV7Generator;
import org.las.sso.logout.BackChannelLogoutOutbox;
import org.las.sso.security.TransientSecurityKeyHasher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.stereotype.Component;

@Component
public final class RefreshTokenFamilyService {

    private static final String HASH_NAMESPACE = "oauth2-refresh-token";

    private final JdbcClient jdbcClient;
    private final TransientSecurityKeyHasher hasher;
    private final UuidV7Generator uuidGenerator;
    private final BackChannelLogoutOutbox backChannelLogout;

    public RefreshTokenFamilyService(
            JdbcClient jdbcClient,
            TransientSecurityKeyHasher hasher,
            UuidV7Generator uuidGenerator,
            BackChannelLogoutOutbox backChannelLogout) {
        this.jdbcClient = jdbcClient;
        this.hasher = hasher;
        this.uuidGenerator = uuidGenerator;
        this.backChannelLogout = backChannelLogout;
    }

    /**
     * Must run inside the transaction that performs the refresh. The history and
     * authorization rows remain locked until the rotated token has been saved.
     */
    public boolean lockAndValidate(String rawRefreshToken) {
        byte[] tokenHash = hash(rawRefreshToken);
        Optional<TokenHistory> history = findHistoryForUpdate(tokenHash);
        if (history.isEmpty()) {
            importLegacyCurrentToken(rawRefreshToken, tokenHash);
            history = findHistoryForUpdate(tokenHash);
        }
        if (history.isEmpty()) {
            return false;
        }

        TokenHistory token = history.orElseThrow();
        if (!"CURRENT".equals(token.status())) {
            if ("ROTATED".equals(token.status())) {
                recordReuseAndRevoke(token);
            }
            return false;
        }

        Optional<ValidationState> validation = jdbcClient.sql("""
                SELECT s.family_absolute_expires_at > CURRENT_TIMESTAMP AS family_active,
                       a.refresh_token_value = :rawToken AS token_matches,
                       a.refresh_token_expires_at > CURRENT_TIMESTAMP AS token_active,
                       u.account_status = 'ACTIVE' AS account_active,
                       pc.state = 'ACTIVE' AS credential_active,
                       pc.credentials_version = s.credentials_version AS credential_version_matches,
                       system_state.security_epoch = s.security_epoch AS security_epoch_matches,
                       metadata.status = 'ACTIVE' AS client_active,
                       metadata.config_version = s.client_config_version AS client_version_matches
                  FROM oauth2_authorization a
                  JOIN oauth2_authorization_security s ON s.authorization_id = a.id
                  JOIN users u ON u.id::text = a.principal_name
                  JOIN password_credentials pc ON pc.user_id = u.id
                  JOIN system_security_state system_state ON system_state.id = 1
                  JOIN registered_client_metadata metadata
                    ON metadata.registered_client_id = a.registered_client_id
                 WHERE a.id = :authorizationId
                   AND s.token_family_id = :familyId
                 FOR UPDATE OF a, s
                """)
                .param("rawToken", rawRefreshToken)
                .param("authorizationId", token.authorizationId())
                .param("familyId", token.tokenFamilyId())
                .query((rs, rowNum) -> new ValidationState(
                        rs.getBoolean("family_active"),
                        rs.getBoolean("token_matches"),
                        rs.getBoolean("token_active"),
                        rs.getBoolean("account_active"),
                        rs.getBoolean("credential_active"),
                        rs.getBoolean("credential_version_matches"),
                        rs.getBoolean("security_epoch_matches"),
                        rs.getBoolean("client_active"),
                        rs.getBoolean("client_version_matches")))
                .optional();
        if (validation.isEmpty()) {
            revokeFamily(token.tokenFamilyId());
            return false;
        }
        ValidationState state = validation.orElseThrow();
        if (!state.valid()) {
            if (state.securityStateInvalid()) {
                recordInvalidationAndRevoke(
                        token, "REFRESH_TOKEN_SECURITY_STATE_INVALID", "WARNING");
            } else {
                revokeFamily(token.tokenFamilyId());
            }
        }
        return state.valid();
    }

    /** Records the current token after the authorization row and family metadata exist. */
    public void recordCurrent(OAuth2Authorization authorization) {
        if (authorization.getRefreshToken() == null) {
            return;
        }
        var refreshToken = authorization.getRefreshToken().getToken();
        byte[] tokenHash = hash(refreshToken.getTokenValue());
        UUID familyId = jdbcClient.sql("""
                SELECT token_family_id
                  FROM oauth2_authorization_security
                 WHERE authorization_id = :authorizationId
                   AND token_family_id IS NOT NULL
                 FOR UPDATE
                """)
                .param("authorizationId", authorization.getId())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Refresh token family metadata is missing"));

        Optional<byte[]> currentHash = jdbcClient.sql("""
                SELECT token_hash
                  FROM oauth2_refresh_token_history
                 WHERE token_family_id = :familyId AND status = 'CURRENT'
                 FOR UPDATE
                """)
                .param("familyId", familyId)
                .query(byte[].class)
                .optional();
        if (currentHash.isPresent() && Arrays.equals(currentHash.orElseThrow(), tokenHash)) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                UPDATE oauth2_refresh_token_history
                   SET status = 'ROTATED', rotated_at = :now
                 WHERE token_family_id = :familyId AND status = 'CURRENT'
                """)
                .param("now", now)
                .param("familyId", familyId)
                .update();
        int inserted = jdbcClient.sql("""
                INSERT INTO oauth2_refresh_token_history
                    (token_hash, token_family_id, authorization_id, status,
                     issued_at, expires_at)
                VALUES (:tokenHash, :familyId, :authorizationId, 'CURRENT',
                        :issuedAt, :expiresAt)
                ON CONFLICT (token_hash) DO NOTHING
                """)
                .param("tokenHash", tokenHash)
                .param("familyId", familyId)
                .param("authorizationId", authorization.getId())
                .param("issuedAt", OffsetDateTime.ofInstant(
                        refreshToken.getIssuedAt(), ZoneOffset.UTC))
                .param("expiresAt", OffsetDateTime.ofInstant(
                        refreshToken.getExpiresAt(), ZoneOffset.UTC))
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("Refresh token history hash already exists");
        }
    }

    private Optional<TokenHistory> findHistoryForUpdate(byte[] tokenHash) {
        return jdbcClient.sql("""
                SELECT token_family_id, authorization_id, status::text AS status
                  FROM oauth2_refresh_token_history
                 WHERE token_hash = :tokenHash
                 FOR UPDATE
                """)
                .param("tokenHash", tokenHash)
                .query(RefreshTokenFamilyService::mapHistory)
                .optional();
    }

    private void importLegacyCurrentToken(String rawToken, byte[] tokenHash) {
        Optional<LegacyToken> legacy = jdbcClient.sql("""
                SELECT a.id AS authorization_id,
                       a.refresh_token_issued_at,
                       a.refresh_token_expires_at,
                       s.token_family_id,
                       s.family_created_at,
                       s.family_absolute_expires_at
                  FROM oauth2_authorization a
                  JOIN oauth2_authorization_security s ON s.authorization_id = a.id
                 WHERE a.refresh_token_value = :rawToken
                 FOR UPDATE OF a, s
                """)
                .param("rawToken", rawToken)
                .query((rs, rowNum) -> new LegacyToken(
                        rs.getString("authorization_id"),
                        rs.getObject("refresh_token_issued_at", OffsetDateTime.class),
                        rs.getObject("refresh_token_expires_at", OffsetDateTime.class),
                        rs.getObject("token_family_id", UUID.class),
                        rs.getObject("family_created_at", OffsetDateTime.class),
                        rs.getObject("family_absolute_expires_at", OffsetDateTime.class)))
                .optional();
        if (legacy.isEmpty()) {
            return;
        }

        LegacyToken token = legacy.orElseThrow();
        UUID familyId = token.familyId() == null ? uuidGenerator.generate() : token.familyId();
        OffsetDateTime createdAt = token.familyCreatedAt() == null
                ? token.issuedAt() : token.familyCreatedAt();
        OffsetDateTime absoluteExpiresAt = token.familyAbsoluteExpiresAt() == null
                ? createdAt.plusDays(14) : token.familyAbsoluteExpiresAt();
        jdbcClient.sql("""
                UPDATE oauth2_authorization_security
                   SET token_family_id = :familyId,
                       family_created_at = :createdAt,
                       family_absolute_expires_at = :absoluteExpiresAt
                 WHERE authorization_id = :authorizationId
                """)
                .param("familyId", familyId)
                .param("createdAt", createdAt)
                .param("absoluteExpiresAt", absoluteExpiresAt)
                .param("authorizationId", token.authorizationId())
                .update();
        jdbcClient.sql("""
                INSERT INTO oauth2_refresh_token_history
                    (token_hash, token_family_id, authorization_id, status,
                     issued_at, expires_at)
                VALUES (:tokenHash, :familyId, :authorizationId, 'CURRENT',
                        :issuedAt, :expiresAt)
                ON CONFLICT (token_hash) DO NOTHING
                """)
                .param("tokenHash", tokenHash)
                .param("familyId", familyId)
                .param("authorizationId", token.authorizationId())
                .param("issuedAt", token.issuedAt())
                .param("expiresAt", token.expiresAt())
                .update();
    }

    private void revokeFamily(UUID familyId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                UPDATE oauth2_refresh_token_history
                   SET status = 'REVOKED', revoked_at = COALESCE(revoked_at, :now)
                 WHERE token_family_id = :familyId AND status <> 'REVOKED'
                """)
                .param("now", now)
                .param("familyId", familyId)
                .update();
        jdbcClient.sql("""
                DELETE FROM oauth2_authorization
                 WHERE id IN (
                     SELECT DISTINCT authorization_id
                       FROM oauth2_refresh_token_history
                      WHERE token_family_id = :familyId
                 )
                """)
                .param("familyId", familyId)
                .update();
    }

    private void recordReuseAndRevoke(TokenHistory token) {
        recordInvalidationAndRevoke(token, "REFRESH_TOKEN_REUSE_DETECTED", "CRITICAL");
    }

    private void recordInvalidationAndRevoke(
            TokenHistory token, String eventType, String severity) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Optional<AuthorizationOwner> owner = jdbcClient.sql("""
                SELECT u.id AS user_id, rc.client_id
                  FROM oauth2_authorization a
                  JOIN users u ON u.id::text = a.principal_name
                  JOIN oauth2_registered_client rc ON rc.id = a.registered_client_id
                 WHERE a.id = :authorizationId
                """)
                .param("authorizationId", token.authorizationId())
                .query((rs, rowNum) -> new AuthorizationOwner(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("client_id")))
                .optional();
        if (owner.isPresent()) {
            AuthorizationOwner authorizationOwner = owner.orElseThrow();
            backChannelLogout.enqueueForAuthorization(
                    token.authorizationId(),
                    authorizationOwner.userId(),
                    eventType,
                    now);
            jdbcClient.sql("""
                    INSERT INTO security_events
                        (id, occurred_at, user_id, event_type, severity,
                         request_id, client_id, details_json)
                    VALUES (:id, :now, :userId, :eventType,
                            :severity, :requestId, :clientId,
                            jsonb_build_object(
                                'tokenFamilyId', CAST(:familyId AS text)))
                    """)
                    .param("id", uuidGenerator.generate())
                    .param("now", now)
                    .param("userId", authorizationOwner.userId())
                    .param("eventType", eventType)
                    .param("severity", severity)
                    .param("requestId", uuidGenerator.generate())
                    .param("clientId", authorizationOwner.clientId())
                    .param("familyId", token.tokenFamilyId())
                    .update();
        }
        revokeFamily(token.tokenFamilyId());
    }

    private byte[] hash(String rawToken) {
        return hasher.hash(HASH_NAMESPACE, rawToken);
    }

    private static TokenHistory mapHistory(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TokenHistory(
                resultSet.getObject("token_family_id", UUID.class),
                resultSet.getString("authorization_id"),
                resultSet.getString("status"));
    }

    private record TokenHistory(UUID tokenFamilyId, String authorizationId, String status) {
    }

    private record AuthorizationOwner(UUID userId, String clientId) {
    }

    private record ValidationState(
            boolean familyActive,
            boolean tokenMatches,
            boolean tokenActive,
            boolean accountActive,
            boolean credentialActive,
            boolean credentialVersionMatches,
            boolean securityEpochMatches,
            boolean clientActive,
            boolean clientVersionMatches) {

        boolean valid() {
            return familyActive
                    && tokenMatches
                    && tokenActive
                    && accountActive
                    && credentialActive
                    && credentialVersionMatches
                    && securityEpochMatches
                    && clientActive
                    && clientVersionMatches;
        }

        boolean securityStateInvalid() {
            return !tokenMatches
                    || !accountActive
                    || !credentialActive
                    || !credentialVersionMatches
                    || !securityEpochMatches
                    || !clientActive
                    || !clientVersionMatches;
        }
    }

    private record LegacyToken(
            String authorizationId,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            UUID familyId,
            OffsetDateTime familyCreatedAt,
            OffsetDateTime familyAbsoluteExpiresAt) {
    }
}
