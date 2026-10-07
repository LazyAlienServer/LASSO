package org.las.sso.reference.revocation;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

final class RevocationWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(RevocationWorker.class);
    static final Duration LEASE = Duration.ofMinutes(2);
    private final JdbcRevocationStore store;
    private final RevocationTokenCipher cipher;
    private final ClientRegistrationRepository registrations;
    private final boolean allowLoopbackHttp;
    private final RestClient http;

    RevocationWorker(
            JdbcRevocationStore store,
            RevocationTokenCipher cipher,
            ClientRegistrationRepository registrations,
            boolean allowLoopbackHttp) {
        this.store = store;
        this.cipher = cipher;
        this.registrations = registrations;
        this.allowLoopbackHttp = allowLoopbackHttp;
        var factory =
                new SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(HttpURLConnection connection, String method)
                            throws IOException {
                        super.prepareConnection(connection, method);
                        connection.setInstanceFollowRedirects(false);
                    }
                };
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        http = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT5S")
    void poll() {
        try {
            // Claim one immediately before HTTP, not a batch whose leases expire waiting in a
            // queue.
            for (int count = 0; count < 10 && !Thread.currentThread().isInterrupted(); count++) {
                var claimed = store.claim(LEASE);
                if (claimed.isEmpty()) break;
                deliver(claimed.get());
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Durable revocation worker unavailable: errorCode=STORAGE_UNAVAILABLE");
        }
    }

    void deliver(RevocationTask task) {
        String failure = null;
        try {
            org.springframework.security.oauth2.client.registration.ClientRegistration registration;
            try {
                registration = registrations.findByRegistrationId(task.registrationId());
            } catch (RuntimeException exception) {
                throw new ConfigurationChanged();
            }
            if (registration == null
                    || !task.issuer().equals(registration.getProviderDetails().getIssuerUri())
                    || !task.clientId().equals(registration.getClientId())
                    || !task.uri()
                            .equals(
                                    JdbcRevocationQueue.endpoint(
                                            registration, allowLoopbackHttp))) {
                throw new ConfigurationChanged();
            }
            String token;
            try {
                token = cipher.decrypt(task);
            } catch (RuntimeException exception) {
                throw new PayloadUnavailable();
            }
            var form = new LinkedMultiValueMap<String, String>();
            form.add("token", token);
            form.add("token_type_hint", task.hint());
            http.post()
                    .uri(task.uri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .headers(
                            headers ->
                                    headers.setBasicAuth(
                                            registration.getClientId(),
                                            registration.getClientSecret()))
                    .body(form)
                    .exchange(
                            (sent, response) -> {
                                if (response.getStatusCode().value() != 200)
                                    throw new HttpRejected(response.getStatusCode().value());
                                return null;
                            });
        } catch (HttpRejected exception) {
            failure = "HTTP_" + exception.status;
        } catch (ConfigurationChanged | IllegalArgumentException exception) {
            failure = "CONFIGURATION_CHANGED";
        } catch (PayloadUnavailable exception) {
            failure = "PAYLOAD_UNAVAILABLE";
        } catch (RuntimeException exception) {
            failure = "TRANSPORT_UNAVAILABLE";
        }

        // Do not treat a failed receipt commit after HTTP200 as remote failure. An expired lease
        // safely retries.
        if (failure == null) {
            store.complete(task);
        } else {
            long delay =
                    ThreadLocalRandom.current()
                            .nextLong(1, Math.min(300L, 1L << task.attempts()) + 1);
            if (store.fail(task, failure, delay) && task.attempts() >= 8) {
                LOGGER.error(
                        "Durable revocation dead letter: taskId={}, errorCode={}",
                        task.id(),
                        failure);
            }
        }
    }

    private static final class HttpRejected extends RuntimeException {
        final int status;

        HttpRejected(int status) {
            this.status = status;
        }
    }

    private static final class ConfigurationChanged extends RuntimeException {}

    private static final class PayloadUnavailable extends RuntimeException {}
}
