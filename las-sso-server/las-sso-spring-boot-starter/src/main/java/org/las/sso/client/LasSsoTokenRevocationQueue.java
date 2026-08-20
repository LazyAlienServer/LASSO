package org.las.sso.client;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

public final class LasSsoTokenRevocationQueue implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LasSsoTokenRevocationQueue.class);
    private static final int MAX_ATTEMPTS = 8;

    private final ScheduledExecutorService executor;
    private final RestClient restClient;

    public LasSsoTokenRevocationQueue() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("las-sso-token-revocation").factory());
    }

    public void enqueue(ClientRegistration registration, String token, String tokenTypeHint) {
        if (token == null || token.isBlank()) {
            return;
        }
        Object endpoint = registration.getProviderDetails()
                .getConfigurationMetadata().get("revocation_endpoint");
        if (!(endpoint instanceof String uri) || uri.isBlank()) {
            LOGGER.warn("OIDC provider did not advertise a revocation endpoint: registrationId={}",
                    registration.getRegistrationId());
            return;
        }
        executor.execute(() -> deliver(new Request(registration, uri, token, tokenTypeHint, 0)));
    }

    private void deliver(Request request) {
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("token", request.token());
            form.add("token_type_hint", request.tokenTypeHint());
            var invocation = restClient.post()
                    .uri(request.uri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            String secret = request.registration().getClientSecret();
            if (secret != null && !secret.isBlank()) {
                invocation = invocation.headers(headers -> headers.setBasicAuth(
                        request.registration().getClientId(), secret));
            } else {
                form.add("client_id", request.registration().getClientId());
            }
            invocation.body(form).retrieve().toBodilessEntity();
        } catch (RuntimeException exception) {
            int nextAttempt = request.attempt() + 1;
            if (nextAttempt >= MAX_ATTEMPTS) {
                LOGGER.error(
                        "Token revocation exhausted retries: registrationId={}",
                        request.registration().getRegistrationId(), exception);
                return;
            }
            long capSeconds = Math.min(300L, 1L << nextAttempt);
            long delaySeconds = ThreadLocalRandom.current().nextLong(1L, capSeconds + 1L);
            executor.schedule(
                    () -> deliver(new Request(
                            request.registration(), request.uri(), request.token(),
                            request.tokenTypeHint(), nextAttempt)),
                    delaySeconds,
                    TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record Request(
            ClientRegistration registration,
            String uri,
            String token,
            String tokenTypeHint,
            int attempt) {
    }
}
