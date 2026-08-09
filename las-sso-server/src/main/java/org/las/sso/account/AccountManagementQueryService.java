package org.las.sso.account;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountManagementQueryService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcClient jdbcClient;

    public AccountManagementQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public Profile profile(UUID userId) {
        return jdbcClient.sql("""
                SELECT display_name, avatar_resource_id, created_at
                  FROM users
                 WHERE id = :userId AND account_status = 'ACTIVE'
                """).param("userId", userId)
                .query((rs, rowNum) -> {
                    OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    return new Profile(
                            rs.getString("display_name"),
                            rs.getString("avatar_resource_id"),
                            DATE_FORMAT.format(createdAt));
                }).single();
    }

    @Transactional(readOnly = true)
    public List<Authorization> authorizations(UUID userId) {
        return jdbcClient.sql("""
                SELECT rc.client_id,
                       rc.client_name,
                       string_agg(DISTINCT COALESCE(a.authorized_scopes, ''), ' ') AS authorized_scopes,
                       MIN(COALESCE(a.access_token_issued_at,
                                    a.refresh_token_issued_at,
                                    a.authorization_code_issued_at)) AS first_authorized_at,
                       MAX(COALESCE(a.access_token_issued_at,
                                    a.refresh_token_issued_at,
                                    a.authorization_code_issued_at)) AS last_used_at
                  FROM oauth2_authorization a
                  JOIN oauth2_registered_client rc ON rc.id = a.registered_client_id
                 WHERE a.principal_name = :principalName
                 GROUP BY rc.client_id, rc.client_name
                 ORDER BY rc.client_name
                """).param("principalName", userId.toString())
                .query((rs, rowNum) -> {
                    String scopes = rs.getString("authorized_scopes");
                    OffsetDateTime firstAuthorizedAt =
                            rs.getObject("first_authorized_at", OffsetDateTime.class);
                    OffsetDateTime lastUsedAt = rs.getObject("last_used_at", OffsetDateTime.class);
                    return new Authorization(
                            rs.getString("client_id"),
                            rs.getString("client_name"),
                            splitScopes(scopes),
                            firstAuthorizedAt == null
                                    ? "尚无记录"
                                    : DATE_TIME_FORMAT.format(firstAuthorizedAt),
                            lastUsedAt == null ? "尚无使用记录" : DATE_TIME_FORMAT.format(lastUsedAt));
                }).list();
    }

    private static List<String> splitScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scopes.split("[,\\s]+"))
                .filter(scope -> !scope.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    public record Profile(String displayName, String avatarResourceId, String createdDate) {
    }

    public record Authorization(
            String clientId,
            String clientName,
            List<String> scopes,
            String firstAuthorizedLabel,
            String lastUsedLabel) {
        public Authorization {
            scopes = List.copyOf(scopes);
        }
    }
}
