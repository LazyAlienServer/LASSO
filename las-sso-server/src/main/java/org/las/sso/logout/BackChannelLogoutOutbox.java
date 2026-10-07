package org.las.sso.logout;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.las.sso.identity.UuidV7Generator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class BackChannelLogoutOutbox {

    private final JdbcClient jdbcClient;
    private final UuidV7Generator uuidGenerator;

    public BackChannelLogoutOutbox(JdbcClient jdbcClient, UuidV7Generator uuidGenerator) {
        this.jdbcClient = jdbcClient;
        this.uuidGenerator = uuidGenerator;
    }

    public int enqueueForUser(UUID userId, String reason, OffsetDateTime now) {
        return enqueue(
                jdbcClient
                        .sql(
                                """
                SELECT DISTINCT rc.client_id, uri.uri, security.sid
                  FROM oauth2_authorization a
                  JOIN oauth2_registered_client rc
                    ON rc.id = a.registered_client_id
                  JOIN registered_client_backchannel_logout_uris uri
                    ON uri.registered_client_id = rc.id
                  LEFT JOIN oauth2_authorization_security security
                    ON security.authorization_id = a.id
                 WHERE a.principal_name = :principalName
                """)
                        .param("principalName", userId.toString())
                        .query(
                                (rs, rowNum) ->
                                        new Delivery(
                                                rs.getString("client_id"),
                                                rs.getString("uri"),
                                                rs.getString("sid")))
                        .list(),
                userId,
                reason,
                now);
    }

    public int enqueueForSession(UUID userId, String sid, String reason, OffsetDateTime now) {
        return enqueue(
                jdbcClient
                        .sql(
                                """
                SELECT DISTINCT rc.client_id, uri.uri, security.sid
                  FROM oauth2_authorization a
                  JOIN oauth2_authorization_security security
                    ON security.authorization_id = a.id
                  JOIN oauth2_registered_client rc
                    ON rc.id = a.registered_client_id
                  JOIN registered_client_backchannel_logout_uris uri
                    ON uri.registered_client_id = rc.id
                 WHERE a.principal_name = :principalName AND security.sid = :sid
                """)
                        .param("principalName", userId.toString())
                        .param("sid", sid)
                        .query(
                                (rs, rowNum) ->
                                        new Delivery(
                                                rs.getString("client_id"),
                                                rs.getString("uri"),
                                                rs.getString("sid")))
                        .list(),
                userId,
                reason,
                now);
    }

    public int enqueueForAuthorization(
            String authorizationId, UUID userId, String reason, OffsetDateTime now) {
        return enqueue(
                jdbcClient
                        .sql(
                                """
                SELECT rc.client_id, uri.uri, security.sid
                  FROM oauth2_authorization a
                  JOIN oauth2_registered_client rc
                    ON rc.id = a.registered_client_id
                  JOIN registered_client_backchannel_logout_uris uri
                    ON uri.registered_client_id = rc.id
                  LEFT JOIN oauth2_authorization_security security
                    ON security.authorization_id = a.id
                 WHERE a.id = :authorizationId
                   AND a.principal_name = :principalName
                """)
                        .param("authorizationId", authorizationId)
                        .param("principalName", userId.toString())
                        .query(
                                (rs, rowNum) ->
                                        new Delivery(
                                                rs.getString("client_id"),
                                                rs.getString("uri"),
                                                rs.getString("sid")))
                        .list(),
                userId,
                reason,
                now);
    }

    public int enqueueForClient(UUID userId, String clientId, String reason, OffsetDateTime now) {
        return enqueue(
                jdbcClient
                        .sql(
                                """
                SELECT DISTINCT rc.client_id, uri.uri, security.sid
                  FROM oauth2_authorization a
                  JOIN oauth2_registered_client rc
                    ON rc.id = a.registered_client_id
                  JOIN registered_client_backchannel_logout_uris uri
                    ON uri.registered_client_id = rc.id
                  LEFT JOIN oauth2_authorization_security security
                    ON security.authorization_id = a.id
                 WHERE a.principal_name = :principalName
                   AND rc.client_id = :clientId
                """)
                        .param("principalName", userId.toString())
                        .param("clientId", clientId)
                        .query(
                                (rs, rowNum) ->
                                        new Delivery(
                                                rs.getString("client_id"),
                                                rs.getString("uri"),
                                                rs.getString("sid")))
                        .list(),
                userId,
                reason,
                now);
    }

    private int enqueue(
            java.util.List<Delivery> deliveries, UUID userId, String reason, OffsetDateTime now) {
        for (Delivery delivery : deliveries) {
            jdbcClient
                    .sql(
                            """
                    INSERT INTO outbox_events
                        (id, aggregate_type, aggregate_id, event_type, payload_json,
                         created_at, available_at)
                    VALUES (:id, 'USER', :userId, 'BACKCHANNEL_LOGOUT_REQUESTED',
                            jsonb_build_object(
                                'userId', :userId,
                                'clientId', :clientId,
                                'uri', :uri,
                                'sid', CAST(:sid AS text),
                                'reason', :reason),
                            :now, :now)
                    """)
                    .param("id", uuidGenerator.generate())
                    .param("userId", userId.toString())
                    .param("clientId", delivery.clientId())
                    .param("uri", delivery.uri())
                    .param("sid", delivery.sid())
                    .param("reason", reason)
                    .param("now", now)
                    .update();
        }
        return deliveries.size();
    }

    private record Delivery(String clientId, String uri, String sid) {}
}
