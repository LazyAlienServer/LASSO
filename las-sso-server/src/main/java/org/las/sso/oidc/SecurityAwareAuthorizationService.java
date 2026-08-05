package org.las.sso.oidc;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.las.sso.identity.LasUserPrincipal;
import org.las.sso.identity.UuidV7Generator;
import org.las.sso.session.UserSessionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class SecurityAwareAuthorizationService implements OAuth2AuthorizationService {

    private final OAuth2AuthorizationService delegate;
    private final JdbcClient jdbcClient;
    private final UserSessionService sessions;
    private final UuidV7Generator uuidGenerator;
    private final RefreshTokenFamilyService tokenFamilies;
    private final org.springframework.transaction.support.TransactionOperations transactions;

    public SecurityAwareAuthorizationService(
            OAuth2AuthorizationService delegate,
            JdbcClient jdbcClient,
            UserSessionService sessions,
            UuidV7Generator uuidGenerator,
            RefreshTokenFamilyService tokenFamilies,
            org.springframework.transaction.support.TransactionOperations transactions) {
        this.delegate = delegate;
        this.jdbcClient = jdbcClient;
        this.sessions = sessions;
        this.uuidGenerator = uuidGenerator;
        this.tokenFamilies = tokenFamilies;
        this.transactions = transactions;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        transactions.executeWithoutResult(status -> {
            delegate.save(authorization);
            persistSecurityMetadata(authorization);
            tokenFamilies.recordCurrent(authorization);
        });
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
        return delegate.findByToken(token, tokenType);
    }

    private void persistSecurityMetadata(OAuth2Authorization authorization) {
        UUID userId;
        try {
            userId = UUID.fromString(authorization.getPrincipalName());
        } catch (IllegalArgumentException exception) {
            return;
        }
        long credentialsVersion = credentialsVersion(authorization, userId);
        String securityEpoch = jdbcClient.sql(
                        "SELECT security_epoch FROM system_security_state WHERE id = 1")
                .query(String.class).single();
        long clientConfigVersion = jdbcClient.sql("""
                SELECT config_version FROM registered_client_metadata
                 WHERE registered_client_id = :registeredClientId
                """).param("registeredClientId", authorization.getRegisteredClientId())
                .query(Long.class).optional().orElse(1L);
        String sid = currentSid();
        boolean hasRefreshToken = authorization.getRefreshToken() != null;
        OffsetDateTime familyCreatedAt = hasRefreshToken
                ? OffsetDateTime.ofInstant(
                        authorization.getRefreshToken().getToken().getIssuedAt(), ZoneOffset.UTC)
                : null;

        jdbcClient.sql("""
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
                """).param("authorizationId", authorization.getId())
                .param("credentialsVersion", credentialsVersion)
                .param("securityEpoch", securityEpoch)
                .param("clientConfigVersion", clientConfigVersion)
                .param("sid", sid)
                .param("tokenFamilyId", hasRefreshToken ? uuidGenerator.generate() : null)
                .param("familyCreatedAt", familyCreatedAt)
                .param("familyAbsoluteExpiresAt", hasRefreshToken ? familyCreatedAt.plusDays(14) : null)
                .update();
    }

    private long credentialsVersion(OAuth2Authorization authorization, UUID userId) {
        Object principalAttribute = authorization.getAttribute(Principal.class.getName());
        if (principalAttribute instanceof Authentication authentication
                && authentication.getPrincipal() instanceof LasUserPrincipal principal) {
            return principal.credentialsVersion();
        }
        return jdbcClient.sql("""
                SELECT credentials_version FROM password_credentials WHERE user_id = :userId
                """).param("userId", userId).query(Long.class).optional().orElse(1L);
    }

    private String currentSid() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return sessions.currentSid(request);
        }
        return null;
    }
}
