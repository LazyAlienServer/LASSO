package org.las.sso.account;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.las.sso.identity.UuidV7Generator;
import org.las.sso.logout.BackChannelLogoutOutbox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountAuthorizationService {

    private final JdbcClient jdbcClient;
    private final BackChannelLogoutOutbox backChannelLogout;
    private final UuidV7Generator uuidGenerator;
    private final Clock clock;

    @Autowired
    public AccountAuthorizationService(
            JdbcClient jdbcClient,
            BackChannelLogoutOutbox backChannelLogout,
            UuidV7Generator uuidGenerator) {
        this(jdbcClient, backChannelLogout, uuidGenerator, Clock.systemUTC());
    }

    AccountAuthorizationService(
            JdbcClient jdbcClient,
            BackChannelLogoutOutbox backChannelLogout,
            UuidV7Generator uuidGenerator,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.backChannelLogout = backChannelLogout;
        this.uuidGenerator = uuidGenerator;
        this.clock = clock;
    }

    @Transactional
    public RevocationResult revoke(UUID userId, String clientId, UUID requestId) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 100) {
            throw new IllegalArgumentException("clientId must be between 1 and 100 characters");
        }

        List<String> authorizationIds = jdbcClient.sql("""
                SELECT a.id
                  FROM oauth2_authorization a
                  JOIN oauth2_registered_client rc ON rc.id = a.registered_client_id
                 WHERE a.principal_name = :principalName
                   AND rc.client_id = :clientId
                 FOR UPDATE OF a
                """)
                .param("principalName", userId.toString())
                .param("clientId", clientId)
                .query(String.class)
                .list();
        if (authorizationIds.isEmpty()) {
            return new RevocationResult(0, 0);
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int logoutRequests = backChannelLogout.enqueueForClient(
                userId, clientId, "USER_AUTHORIZATION_REVOKED", now);

        jdbcClient.sql("""
                DELETE FROM oauth2_authorization_consent consent
                 USING oauth2_registered_client rc
                 WHERE consent.registered_client_id = rc.id
                   AND consent.principal_name = :principalName
                   AND rc.client_id = :clientId
                """)
                .param("principalName", userId.toString())
                .param("clientId", clientId)
                .update();

        int deleted = jdbcClient.sql("""
                DELETE FROM oauth2_authorization a
                 USING oauth2_registered_client rc
                 WHERE a.registered_client_id = rc.id
                   AND a.principal_name = :principalName
                   AND rc.client_id = :clientId
                """)
                .param("principalName", userId.toString())
                .param("clientId", clientId)
                .update();

        jdbcClient.sql("""
                INSERT INTO security_events
                    (id, occurred_at, user_id, event_type, severity,
                     request_id, client_id, details_json)
                VALUES (:id, :now, :userId, 'AUTHORIZATION_REVOKED', 'INFO',
                        :requestId, :clientId,
                        jsonb_build_object(
                            'authorizationCount', :authorizationCount,
                            'logoutRequestCount', :logoutRequestCount))
                """)
                .param("id", uuidGenerator.generate())
                .param("now", now)
                .param("userId", userId)
                .param("requestId", requestId)
                .param("clientId", clientId)
                .param("authorizationCount", deleted)
                .param("logoutRequestCount", logoutRequests)
                .update();
        return new RevocationResult(deleted, logoutRequests);
    }

    public record RevocationResult(int authorizationCount, int logoutRequestCount) {
    }
}
