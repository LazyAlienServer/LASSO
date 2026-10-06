package org.las.sso.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.las.sso.config.LasSsoProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceAccessEligibilityService {

    private final JdbcClient jdbcClient;
    private final LasSsoProperties properties;

    public ServiceAccessEligibilityService(
            JdbcClient jdbcClient,
            LasSsoProperties properties) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Decision evaluate(UUID subject) {
        return jdbcClient.sql("""
                SELECT u.id AS subject_id, state.security_epoch AS state_epoch,
                       u.account_status::text AS account_status,
                       u.service_access_state_version,
                       u.service_access_changed_at,
                       pc.state::text AS credential_state,
                       deletion.status::text AS deletion_status
                  FROM system_security_state state
                  LEFT JOIN users u ON u.id = :subject
                  LEFT JOIN password_credentials pc ON pc.user_id = u.id
                  LEFT JOIN LATERAL (
                       SELECT status
                         FROM account_deletion_requests
                        WHERE user_id = u.id
                          AND status IN ('PENDING', 'HELD')
                        ORDER BY requested_at DESC
                        LIMIT 1
                  ) deletion ON true
                 WHERE state.id = 1
                """).param("subject", subject)
                .query((rs, rowNum) -> rs.getObject("subject_id", UUID.class) == null
                        ? denied(subject, rs.getString("state_epoch"), "PRINCIPAL_NOT_FOUND", 0, null)
                        : decide(
                                subject,
                                rs.getString("state_epoch"),
                                rs.getString("account_status"),
                                rs.getLong("service_access_state_version"),
                                rs.getObject("service_access_changed_at", OffsetDateTime.class),
                                rs.getString("credential_state"),
                                rs.getString("deletion_status")))
                .optional()
                .orElseThrow(() -> new DataRetrievalFailureException("Service-access security epoch is unavailable"));
    }

    /**
     * Advances the version of the subject's service-access state in the same
     * transaction as the account, credential, or deletion-state mutation.
     */
    public void advanceStateVersion(UUID subject, OffsetDateTime changedAt) {
        int updated = jdbcClient.sql("""
                UPDATE users
                   SET service_access_state_version = service_access_state_version + 1,
                       service_access_changed_at = :changedAt
                 WHERE id = :subject
                """).param("subject", subject).param("changedAt", changedAt).update();
        if (updated != 1) {
            throw new IllegalStateException("Service-access subject disappeared during update");
        }
    }

    private Decision decide(
            UUID subject,
            String stateEpoch,
            String accountStatus,
            long stateVersion,
            OffsetDateTime changedAt,
            String credentialState,
            String deletionStatus) {
        if ("DELETED".equals(accountStatus)) {
            return denied(subject, stateEpoch, "ACCOUNT_DELETED", stateVersion, changedAt);
        }
        if ("FROZEN".equals(accountStatus)) {
            return denied(subject, stateEpoch, "ACCOUNT_FROZEN", stateVersion, changedAt);
        }
        if ("PENDING".equals(deletionStatus)) {
            return denied(subject, stateEpoch, "DELETION_PENDING", stateVersion, changedAt);
        }
        if ("HELD".equals(deletionStatus)) {
            return denied(subject, stateEpoch, "DELETION_HELD", stateVersion, changedAt);
        }
        if ("PASSWORD_CHANGE_REQUIRED".equals(credentialState)) {
            return denied(subject, stateEpoch, "PASSWORD_CHANGE_REQUIRED", stateVersion, changedAt);
        }
        if ("DISABLED".equals(credentialState)) {
            return denied(subject, stateEpoch, "CREDENTIALS_DISABLED", stateVersion, changedAt);
        }
        if ("ACTIVE".equals(accountStatus) && "ACTIVE".equals(credentialState)) {
            return new Decision(
                    properties.issuer().toString(),
                    subject,
                    "ALLOWED",
                    "ACTIVE",
                    stateEpoch,
                    stateVersion,
                    changedAt);
        }
        return denied(subject, stateEpoch, "ACCOUNT_STATE_INVALID", stateVersion, changedAt);
    }

    private Decision denied(
            UUID subject,
            String stateEpoch,
            String reason,
            long stateVersion,
            OffsetDateTime changedAt) {
        return new Decision(
                properties.issuer().toString(),
                subject,
                "DENIED",
                reason,
                stateEpoch,
                stateVersion,
                changedAt);
    }

    public record Decision(
            String issuer,
            UUID subject,
            String decision,
            String reason,
            String stateEpoch,
            long stateVersion,
            OffsetDateTime changedAt) {
        public Decision {
            if (stateEpoch == null || stateEpoch.isBlank()) {
                throw new DataRetrievalFailureException("Service-access security epoch is unavailable");
            }
        }
    }
}
