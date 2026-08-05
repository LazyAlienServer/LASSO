package org.las.sso.security;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Repository
public class PostgresCaptchaChallengeStore {

    private final JdbcClient jdbcClient;

    public PostgresCaptchaChallengeStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void create(
            UUID id,
            String purpose,
            byte[] challengeDigest,
            byte[] ipPrefixHash,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {
        jdbcClient.sql("""
                INSERT INTO captcha_challenges
                    (id, purpose, challenge_digest, ip_prefix_hash, created_at, expires_at)
                VALUES (:id, :purpose, :digest, :ipHash, :createdAt, :expiresAt)
                """).param("id", id).param("purpose", purpose).param("digest", challengeDigest)
                .param("ipHash", ipPrefixHash, java.sql.Types.BINARY)
                .param("createdAt", createdAt).param("expiresAt", expiresAt).update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(
            UUID id,
            String purpose,
            byte[] challengeDigest,
            byte[] ipPrefixHash,
            OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE captcha_challenges
                   SET consumed_at = :now
                 WHERE id = :id
                   AND purpose = :purpose
                   AND challenge_digest = :digest
                   AND (ip_prefix_hash IS NULL OR ip_prefix_hash = :ipHash)
                   AND consumed_at IS NULL
                   AND expires_at > :now
                """).param("now", now).param("id", id).param("purpose", purpose)
                .param("digest", challengeDigest)
                .param("ipHash", ipPrefixHash, java.sql.Types.BINARY).update() == 1;
    }
}
