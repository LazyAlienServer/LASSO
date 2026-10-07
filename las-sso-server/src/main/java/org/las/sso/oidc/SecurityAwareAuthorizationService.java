package org.las.sso.oidc;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.identity.UuidV7Generator;
import org.las.sso.security.RecentAuthenticationService;
import org.las.sso.security.TransientSecurityKeyHasher;
import org.las.sso.session.UserSessionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class SecurityAwareAuthorizationService implements OAuth2AuthorizationService {

    private static final OAuth2TokenType ID_TOKEN_TYPE = new OAuth2TokenType("id_token");

    public static final String PASSWORD_AUTH_TIME_ATTRIBUTE =
            SecurityAwareAuthorizationService.class.getName() + ".PASSWORD_AUTH_TIME";

    private final OAuth2AuthorizationService delegate;
    private final JdbcClient jdbcClient;
    private final UserSessionService sessions;
    private final RecentAuthenticationService recentAuthentication;
    private final UuidV7Generator uuidGenerator;
    private final RefreshTokenFamilyService tokenFamilies;
    private final org.springframework.transaction.support.TransactionOperations transactions;
    private final TransientSecurityKeyHasher keyHasher;

    public SecurityAwareAuthorizationService(
            OAuth2AuthorizationService delegate,
            JdbcClient jdbcClient,
            UserSessionService sessions,
            RecentAuthenticationService recentAuthentication,
            UuidV7Generator uuidGenerator,
            RefreshTokenFamilyService tokenFamilies,
            org.springframework.transaction.support.TransactionOperations transactions,
            TransientSecurityKeyHasher keyHasher) {
        this.delegate = delegate;
        this.jdbcClient = jdbcClient;
        this.sessions = sessions;
        this.recentAuthentication = recentAuthentication;
        this.uuidGenerator = uuidGenerator;
        this.tokenFamilies = tokenFamilies;
        this.transactions = transactions;
        this.keyHasher = keyHasher;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        OAuth2Authorization withEvidence = capturePasswordEvidence(authorization);
        transactions.executeWithoutResult(
                status -> {
                    String clientStatus =
                            jdbcClient
                                    .sql(
                                            """
                    SELECT status::text FROM registered_client_metadata
                     WHERE registered_client_id = :registeredClientId
                    """)
                                    .param(
                                            "registeredClientId",
                                            authorization.getRegisteredClientId())
                                    .query(String.class)
                                    .optional()
                                    .orElse("ACTIVE");
                    if ("DISABLED".equals(clientStatus)) {
                        throw new org.springframework.security.oauth2.core
                                .OAuth2AuthenticationException(
                                new org.springframework.security.oauth2.core.OAuth2Error(
                                        org.springframework.security.oauth2.core.OAuth2ErrorCodes
                                                .INVALID_CLIENT,
                                        "Client is disabled",
                                        null),
                                "Client is disabled");
                    }
                    // Preserve the currently stored hint before refresh replaces it, including
                    // pre-V15 authorizations.
                    OAuth2Authorization previous = delegate.findById(withEvidence.getId());
                    if (previous != null) persistIdTokenHint(previous);
                    delegate.save(withEvidence);
                    persistSecurityMetadata(withEvidence);
                    tokenFamilies.recordCurrent(withEvidence);
                    persistIdTokenHint(withEvidence);
                });
    }

    private OAuth2Authorization capturePasswordEvidence(OAuth2Authorization authorization) {
        // Only the initial browser authorization may snapshot evidence. Never consult a
        // live session at code exchange/refresh or retroactively upgrade old authorizations.
        if (authorization.getAttribute(PASSWORD_AUTH_TIME_ATTRIBUTE) != null
                || delegate.findById(authorization.getId()) != null
                || !(RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes attributes)
                || !(authorization.getAttribute(Principal.class.getName())
                        instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof LasUserPrincipal principal)
                || !principal.getName().equals(authorization.getPrincipalName())) {
            return authorization;
        }
        var current =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (current == null
                || !current.isAuthenticated()
                || !principal.getName().equals(current.getName())) {
            return authorization;
        }
        return recentAuthentication
                .passwordAuthenticatedAt(attributes.getRequest())
                .map(
                        at ->
                                OAuth2Authorization.from(authorization)
                                        .attribute(
                                                PASSWORD_AUTH_TIME_ATTRIBUTE, at.getEpochSecond())
                                        .build())
                .orElse(authorization);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        OAuth2Authorization authorization = delegate.findByToken(token, tokenType);
        // Only the logout provider requests this type. Never broaden Bearer, code or refresh
        // lookup.
        if (authorization != null || !ID_TOKEN_TYPE.equals(tokenType)) {
            return authorization;
        }
        String authorizationId =
                jdbcClient
                        .sql(
                                """
                SELECT authorization_id
                  FROM oauth2_id_token_hint_history
                 WHERE token_hash = :tokenHash
                """)
                        .param("tokenHash", keyHasher.hash("oidc-id-token-hint", token))
                        .query(String.class)
                        .optional()
                        .orElse(null);
        return authorizationId == null ? null : delegate.findById(authorizationId);
    }

    private void persistIdTokenHint(OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OidcIdToken> idToken = authorization.getToken(OidcIdToken.class);
        if (idToken == null || idToken.getToken().getTokenValue() == null) {
            return;
        }
        jdbcClient
                .sql(
                        """
                INSERT INTO oauth2_id_token_hint_history(token_hash, authorization_id, created_at)
                VALUES (:tokenHash, :authorizationId, CURRENT_TIMESTAMP)
                ON CONFLICT (token_hash) DO NOTHING
                """)
                .param(
                        "tokenHash",
                        keyHasher.hash("oidc-id-token-hint", idToken.getToken().getTokenValue()))
                .param("authorizationId", authorization.getId())
                .update();
    }

    private void persistSecurityMetadata(OAuth2Authorization authorization) {
        UUID userId;
        try {
            userId = UUID.fromString(authorization.getPrincipalName());
        } catch (IllegalArgumentException exception) {
            return;
        }
        long credentialsVersion = credentialsVersion(authorization, userId);
        String securityEpoch =
                jdbcClient
                        .sql("SELECT security_epoch FROM system_security_state WHERE id = 1")
                        .query(String.class)
                        .single();
        long clientConfigVersion =
                jdbcClient
                        .sql(
                                """
                SELECT config_version FROM registered_client_metadata
                 WHERE registered_client_id = :registeredClientId
                """)
                        .param("registeredClientId", authorization.getRegisteredClientId())
                        .query(Long.class)
                        .optional()
                        .orElse(1L);
        String sid = currentSid();
        boolean hasRefreshToken = authorization.getRefreshToken() != null;
        OffsetDateTime familyCreatedAt =
                hasRefreshToken
                        ? OffsetDateTime.ofInstant(
                                authorization.getRefreshToken().getToken().getIssuedAt(),
                                ZoneOffset.UTC)
                        : null;

        jdbcClient
                .sql(
                        """
                INSERT INTO oauth2_authorization_security
                    (authorization_id, credentials_version, security_epoch,
                     client_config_version, sid, token_family_id,
                     family_created_at, family_absolute_expires_at)
                VALUES (:authorizationId, :credentialsVersion, :securityEpoch,
                        :clientConfigVersion, :sid, :tokenFamilyId,
                        :familyCreatedAt, :familyAbsoluteExpiresAt)
                ON CONFLICT (authorization_id) DO UPDATE SET
                    credentials_version = EXCLUDED.credentials_version,
                    security_epoch = EXCLUDED.security_epoch,
                    client_config_version = EXCLUDED.client_config_version,
                    sid = COALESCE(oauth2_authorization_security.sid, EXCLUDED.sid),
                    token_family_id = COALESCE(oauth2_authorization_security.token_family_id,
                                               EXCLUDED.token_family_id),
                    family_created_at = COALESCE(oauth2_authorization_security.family_created_at,
                                                 EXCLUDED.family_created_at),
                    family_absolute_expires_at = COALESCE(
                        oauth2_authorization_security.family_absolute_expires_at,
                        EXCLUDED.family_absolute_expires_at)
                """)
                .param("authorizationId", authorization.getId())
                .param("credentialsVersion", credentialsVersion)
                .param("securityEpoch", securityEpoch)
                .param("clientConfigVersion", clientConfigVersion)
                .param("sid", sid)
                .param("tokenFamilyId", hasRefreshToken ? uuidGenerator.generate() : null)
                .param("familyCreatedAt", familyCreatedAt)
                .param(
                        "familyAbsoluteExpiresAt",
                        hasRefreshToken ? familyCreatedAt.plusDays(14) : null)
                .update();
    }

    private long credentialsVersion(OAuth2Authorization authorization, UUID userId) {
        Object principalAttribute = authorization.getAttribute(Principal.class.getName());
        if (principalAttribute instanceof Authentication authentication
                && authentication.getPrincipal() instanceof LasUserPrincipal principal) {
            return principal.credentialsVersion();
        }
        return jdbcClient
                .sql(
                        """
                SELECT credentials_version FROM password_credentials WHERE user_id = :userId
                """)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElse(1L);
    }

    private String currentSid() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return sessions.currentSid(request);
        }
        return null;
    }
}
