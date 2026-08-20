package org.las.sso.logout;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class BackChannelLogoutDelivery {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackChannelLogoutDelivery.class);
    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = 10;
    private static final int MAX_ATTEMPTS = 8;

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactions;
    private final RestClient restClient;
    private final LogoutTokenIssuer tokenIssuer;

    public BackChannelLogoutDelivery(
            JdbcClient jdbcClient,
            TransactionTemplate transactions,
            LogoutTokenIssuer tokenIssuer) {
        this.jdbcClient = jdbcClient;
        this.transactions = transactions;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.tokenIssuer = tokenIssuer;
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT5S")
    public void deliverPending() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deliveries = claimBatch().stream()
                    .map(event -> executor.submit(() -> deliver(event)))
                    .toList();
            for (var delivery : deliveries) {
                try {
                    delivery.get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException exception) {
                    LOGGER.error("Unexpected back-channel delivery worker failure", exception.getCause());
                }
            }
        }
    }

    private void deliver(ClaimedEvent event) {
        if ("GLOBAL_LOGOUT_REQUESTED".equals(event.eventType())) {
            complete(event);
            return;
        }
        try {
            String logoutToken = tokenIssuer.issue(
                    event.clientId(), event.userId(), event.sid());
            var form = new LinkedMultiValueMap<String, String>();
            form.add("logout_token", logoutToken);
            restClient.post()
                    .uri(event.uri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            complete(event);
        } catch (RuntimeException exception) {
            retry(event, exception);
        }
    }

    private List<ClaimedEvent> claimBatch() {
        return transactions.execute(status -> {
            OffsetDateTime now = now();
            UUID claimToken = UUID.randomUUID();
            return jdbcClient.sql("""
                    WITH candidates AS (
                        SELECT id
                          FROM outbox_events
                         WHERE processed_at IS NULL
                           AND dead_lettered_at IS NULL
                           AND event_type IN ('BACKCHANNEL_LOGOUT_REQUESTED',
                                              'GLOBAL_LOGOUT_REQUESTED')
                           AND available_at <= :now
                           AND (claimed_at IS NULL OR claimed_at < :claimExpiredBefore)
                         ORDER BY available_at, created_at
                         LIMIT :batchSize
                         FOR UPDATE SKIP LOCKED
                    )
                    UPDATE outbox_events event
                       SET claim_token = :claimToken, claimed_at = :now
                      FROM candidates
                     WHERE event.id = candidates.id
                    RETURNING event.id, event.event_type,
                              event.payload_json ->> 'userId' AS user_id,
                              event.payload_json ->> 'clientId' AS client_id,
                              event.payload_json ->> 'uri' AS uri,
                              event.payload_json ->> 'sid' AS sid,
                              event.attempt_count
                    """).param("now", now)
                    .param("claimExpiredBefore", now.minus(CLAIM_TIMEOUT))
                    .param("batchSize", BATCH_SIZE)
                    .param("claimToken", claimToken)
                    .query((rs, rowNum) -> new ClaimedEvent(
                            rs.getObject("id", UUID.class),
                            claimToken,
                            rs.getString("event_type"),
                            rs.getString("user_id"),
                            rs.getString("client_id"),
                            rs.getString("uri"),
                            rs.getString("sid"),
                            rs.getInt("attempt_count")))
                    .list();
        });
    }

    private void complete(ClaimedEvent event) {
        jdbcClient.sql("""
                UPDATE outbox_events
                   SET processed_at = :now, claim_token = NULL, claimed_at = NULL,
                       last_error_code = NULL
                 WHERE id = :id AND claim_token = :claimToken AND processed_at IS NULL
                """).param("now", now()).param("id", event.id())
                .param("claimToken", event.claimToken()).update();
    }

    private void retry(ClaimedEvent event, RuntimeException exception) {
        int nextAttempt = event.attemptCount() + 1;
        String errorCode = exception.getClass().getSimpleName();
        if (errorCode.length() > 128) {
            errorCode = errorCode.substring(0, 128);
        }
        OffsetDateTime now = now();
        if (nextAttempt >= MAX_ATTEMPTS) {
            deadLetter(event, nextAttempt, errorCode, now, exception);
            return;
        }
        long delayCapSeconds = Math.min(300L, 1L << Math.min(nextAttempt, 8));
        long delaySeconds = ThreadLocalRandom.current().nextLong(1L, delayCapSeconds + 1L);
        jdbcClient.sql("""
                UPDATE outbox_events
                   SET attempt_count = :attemptCount,
                       available_at = :availableAt,
                       last_error_code = :errorCode,
                       claim_token = NULL,
                       claimed_at = NULL
                 WHERE id = :id AND claim_token = :claimToken AND processed_at IS NULL
                """).param("attemptCount", nextAttempt)
                .param("availableAt", now.plusSeconds(delaySeconds))
                .param("errorCode", errorCode)
                .param("id", event.id())
                .param("claimToken", event.claimToken()).update();
    }

    private void deadLetter(
            ClaimedEvent event,
            int attemptCount,
            String errorCode,
            OffsetDateTime now,
            RuntimeException exception) {
        jdbcClient.sql("""
                UPDATE outbox_events
                   SET attempt_count = :attemptCount,
                       dead_lettered_at = :now,
                       last_error_code = :errorCode,
                       claim_token = NULL,
                       claimed_at = NULL
                 WHERE id = :id AND claim_token = :claimToken
                   AND processed_at IS NULL AND dead_lettered_at IS NULL
                """).param("attemptCount", attemptCount)
                .param("now", now)
                .param("errorCode", errorCode)
                .param("id", event.id())
                .param("claimToken", event.claimToken()).update();
        LOGGER.error(
                "Back-channel logout delivery exhausted retries: eventId={}, clientId={}",
                event.id(), event.clientId(), exception);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record ClaimedEvent(
            UUID id,
            UUID claimToken,
            String eventType,
            String userId,
            String clientId,
            String uri,
            String sid,
            int attemptCount) {
    }
}
