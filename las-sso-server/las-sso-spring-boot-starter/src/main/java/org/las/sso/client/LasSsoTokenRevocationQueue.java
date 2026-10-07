package org.las.sso.client;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
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

public final class LasSsoTokenRevocationQueue implements AutoCloseable, LasSsoRevocationTaskSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(LasSsoTokenRevocationQueue.class);
    private static final int MAX_ATTEMPTS = 8;

    private final ScheduledExecutorService executor;
    private final RestClient restClient;

    public LasSsoTokenRevocationQueue() {
        this(
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("las-sso-token-revocation").factory()));
    }

    /**
     * Test seam for observing the retry schedule; the public queue uses the unchanged production
     * scheduler.
     */
    LasSsoTokenRevocationQueue(ScheduledExecutorService executor) {
        var requestFactory =
                new SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(HttpURLConnection connection, String method)
                            throws IOException {
                        super.prepareConnection(connection, method);
                        connection.setInstanceFollowRedirects(false);
                    }
                };
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.executor = executor;
    }

    @Override
    public void submit(ClientRegistration registration, String token, String tokenTypeHint) {
        if (registration == null
                || token == null
                || token.isBlank()
                || token.length() > 16384
                || !("refresh_token".equals(tokenTypeHint) || "access_token".equals(tokenTypeHint)))
            throw new IllegalArgumentException("Invalid revocation submission");
        Object endpoint =
                registration
                        .getProviderDetails()
                        .getConfigurationMetadata()
                        .get("revocation_endpoint");
        if (!(endpoint instanceof String uri) || uri.isBlank()) {
            throw new IllegalStateException("Revocation endpoint unavailable");
        }
        try {
            URI parsed = URI.create(uri);
            boolean loopback =
                    "127.0.0.1".equals(parsed.getHost()) || "[::1]".equals(parsed.getHost());
            if (parsed.getHost() == null
                    || parsed.getUserInfo() != null
                    || parsed.getQuery() != null
                    || parsed.getFragment() != null
                    || !("https".equals(parsed.getScheme())
                            || "http".equals(parsed.getScheme()) && loopback)
                    || registration.getClientSecret() == null
                    || registration.getClientSecret().isBlank()
                    || !org.springframework.security.oauth2.core.ClientAuthenticationMethod
                            .CLIENT_SECRET_BASIC
                            .equals(registration.getClientAuthenticationMethod()))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Revocation endpoint or confidential client invalid");
        }
        executor.execute(() -> deliver(new Request(registration, uri, token, tokenTypeHint, 0)));
    }

    /** Compatibility alias for existing consumers; new integrations use {@link #submit}. */
    public void enqueue(ClientRegistration registration, String token, String tokenTypeHint) {
        submit(registration, token, tokenTypeHint);
    }

    private void deliver(Request request) {
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("token", request.token());
            form.add("token_type_hint", request.tokenTypeHint());
            var invocation =
                    restClient
                            .post()
                            .uri(request.uri())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            String secret = request.registration().getClientSecret();
            if (secret != null && !secret.isBlank()) {
                invocation =
                        invocation.headers(
                                headers ->
                                        headers.setBasicAuth(
                                                request.registration().getClientId(), secret));
            } else {
                form.add("client_id", request.registration().getClientId());
            }
            invocation
                    .body(form)
                    .exchange(
                            (sent, response) -> {
                                if (response.getStatusCode().value() != 200) {
                                    throw new RevocationResponseException(
                                            response.getStatusCode().value());
                                }
                                return null;
                            });
        } catch (RuntimeException exception) {
            int nextAttempt = request.attempt() + 1;
            if (nextAttempt >= MAX_ATTEMPTS) {
                LOGGER.error(
                        "Token revocation exhausted retries: registrationId={}, errorCode={}",
                        request.registration().getRegistrationId(),
                        exception instanceof RevocationResponseException rejected
                                ? "HTTP_" + rejected.status
                                : exception.getClass().getSimpleName());
                return;
            }
            long capSeconds = Math.min(300L, 1L << nextAttempt);
            long delaySeconds = ThreadLocalRandom.current().nextLong(1L, capSeconds + 1L);
            executor.schedule(
                    () ->
                            deliver(
                                    new Request(
                                            request.registration(),
                                            request.uri(),
                                            request.token(),
                                            request.tokenTypeHint(),
                                            nextAttempt)),
                    delaySeconds,
                    TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class RevocationResponseException extends RuntimeException {
        private final int status;

        private RevocationResponseException(int status) {
            super("Revocation endpoint did not acknowledge revocation");
            this.status = status;
        }
    }

    private record Request(
            ClientRegistration registration,
            String uri,
            String token,
            String tokenTypeHint,
            int attempt) {}
}
