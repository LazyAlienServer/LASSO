package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.context.WebApplicationContext;

/** Real starter security chain/manager/repository; only the remote OP and initial login are fixtures. */
class LasSsoSessionChainIntegrationTest {

    @Test
    void defaultChainRefreshesSameSubjectsSessionsUsingTheirOwnRefreshTokens() throws Exception {
        try (var endpoint = new TokenEndpoint()) {
            var sink = new RecordingSink();
            runner(endpoint, sink).run(context -> {
                assertThat(context).hasNotFailed();
                var repository = context.getBean(LasSsoAuthorizedClientRepository.class);
                var registration = context.getBean(ClientRegistrationRepository.class)
                        .findByRegistrationId("las-sso");
                var authentication = authentication();
                var a = loginFixture(context, repository, registration, authentication, "A", true);
                var b = loginFixture(context, repository, registration, authentication, "B", true);

                var responseA = perform(context, a, "GET", "/api/private");
                var responseB = perform(context, b, "GET", "/api/private");

                assertThat(responseA.getStatus()).isEqualTo(200);
                assertThat(responseB.getStatus()).isEqualTo(200);
                assertThat(endpoint.refreshTokens).containsExactly("refresh-A", "refresh-B");
                assertThat(load(context, repository, a, authentication).getAccessToken().getTokenValue())
                        .isEqualTo("new-access-A");
                assertThat(load(context, repository, b, authentication).getAccessToken().getTokenValue())
                        .isEqualTo("new-access-B");
                assertThat(load(context, repository, a, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("new-refresh-A");
                assertThat(load(context, repository, b, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("new-refresh-B");
                assertThat(sink.tokens).isEmpty();
            });
        }
    }

    @Test
    void csrfProtectedLogoutQueuesOnlyTheCurrentSessionsTokenAndLeavesTheOtherSessionUsable()
            throws Exception {
        try (var endpoint = new TokenEndpoint()) {
            var sink = new RecordingSink();
            runner(endpoint, sink).run(context -> {
                assertThat(context).hasNotFailed();
                var repository = context.getBean(LasSsoAuthorizedClientRepository.class);
                var registration = context.getBean(ClientRegistrationRepository.class)
                        .findByRegistrationId("las-sso");
                var authentication = authentication();
                var a = loginFixture(context, repository, registration, authentication, "A", false);
                var b = loginFixture(context, repository, registration, authentication, "B", false);

                var response = logout(context, a);

                assertThat(response.getStatus()).isEqualTo(302);
                assertThat(a.isInvalid()).isTrue();
                assertThat(b.isInvalid()).isFalse();
                assertThat(sink.tokens).containsExactly("refresh-A");
                assertThat(sink.hints).containsExactly("refresh_token");
                assertThat(perform(context, b, "GET", "/api/private").getStatus()).isEqualTo(200);
                assertThat(load(context, repository, b, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("refresh-B");
                assertThat(endpoint.refreshTokens).isEmpty();
            });
        }
    }

    @Test
    void logoutWithoutCsrfDoesNotEndTheSessionOrSubmitRevocation() throws Exception {
        try (var endpoint = new TokenEndpoint()) {
            var sink = new RecordingSink();
            runner(endpoint, sink).run(context -> {
                assertThat(context).hasNotFailed();
                var repository = context.getBean(LasSsoAuthorizedClientRepository.class);
                var registration = context.getBean(ClientRegistrationRepository.class)
                        .findByRegistrationId("las-sso");
                var authentication = authentication();
                var a = loginFixture(context, repository, registration, authentication, "A", false);

                assertThat(perform(context, a, "POST", "/api/auth/logout").getStatus()).isEqualTo(403);
                assertThat(a.isInvalid()).isFalse();
                assertThat(sink.attempts).isZero();
                assertThat(load(context, repository, a, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("refresh-A");
            });
        }
    }

    @Test
    void rejectedRefreshInvalidatesOnlyItsOwningSession() throws Exception {
        try (var endpoint = new TokenEndpoint()) {
            endpoint.rejectedToken = "refresh-A";
            runner(endpoint, new RecordingSink()).run(context -> {
                assertThat(context).hasNotFailed();
                var repository = context.getBean(LasSsoAuthorizedClientRepository.class);
                var registration = context.getBean(ClientRegistrationRepository.class)
                        .findByRegistrationId("las-sso");
                var authentication = authentication();
                var a = loginFixture(context, repository, registration, authentication, "A", true);
                var b = loginFixture(context, repository, registration, authentication, "B", true);

                var rejected = perform(context, a, "GET", "/api/private");

                assertThat(rejected.getStatus()).isEqualTo(401);
                assertThat(rejected.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(rejected.getContentAsString()).contains("SESSION_EXPIRED");
                assertThat(a.isInvalid()).isTrue();
                assertThat(b.isInvalid()).isFalse();
                assertThat(perform(context, b, "GET", "/api/private").getStatus()).isEqualTo(200);
                assertThat(endpoint.refreshTokens).containsExactly("refresh-A", "refresh-B");
                assertThat(load(context, repository, b, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("new-refresh-B");
            });
        }
    }

    @Test
    void logoutUsesTheSameLasRepositoryEvenWhenAnotherSpringRepositoryIsPrimary() throws Exception {
        try (var endpoint = new TokenEndpoint()) {
            var sink = new RecordingSink();
            runner(endpoint, sink).withUserConfiguration(OtherRepository.class).run(context -> {
                assertThat(context).hasNotFailed();
                var repository = context.getBean(LasSsoAuthorizedClientRepository.class);
                var registration = context.getBean(ClientRegistrationRepository.class)
                        .findByRegistrationId("las-sso");
                var authentication = authentication();
                var a = loginFixture(context, repository, registration, authentication, "A", false);

                assertThat(logout(context, a).getStatus()).isEqualTo(302);
                assertThat(sink.tokens).containsExactly("refresh-A");
                assertThat(context.getBean(UnrelatedRepository.class).calls).isZero();
            });
        }
    }

    @Test
    void failedRevocationSubmissionStillRemovesTokensAndEndsOnlyTheLocalSession() throws Exception {
        try (var endpoint = new TokenEndpoint()) {
            var sink = new RecordingSink();
            runner(endpoint, sink).run(context -> {
                assertThat(context).hasNotFailed();
                var repository = context.getBean(LasSsoAuthorizedClientRepository.class);
                var registration = context.getBean(ClientRegistrationRepository.class)
                        .findByRegistrationId("las-sso");
                var authentication = authentication();
                var a = loginFixture(context, repository, registration, authentication, "A", false);
                var b = loginFixture(context, repository, registration, authentication, "B", false);
                sink.beforeSubmit = () -> {
                    assertThat(a.isInvalid()).isTrue();
                    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                    throw new IllegalStateException("Fixture sink unavailable");
                };

                assertThat(logout(context, a).getStatus()).isEqualTo(302);
                assertThat(a.isInvalid()).isTrue();
                assertThat(b.isInvalid()).isFalse();
                assertThat(sink.attempts).isEqualTo(1);
                assertThat(sink.tokens).isEmpty();
                assertThat(perform(context, b, "GET", "/api/private").getStatus()).isEqualTo(200);
                assertThat(load(context, repository, b, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("refresh-B");
            });
        }
    }

    @Test
    void concurrentRequestsForOneSessionUseARotatingRefreshTokenOnlyOnce() throws Exception {
        try (var endpoint = new TokenEndpoint()) {
            runner(endpoint, new RecordingSink()).run(context -> {
                assertThat(context).hasNotFailed();
                var repository = context.getBean(LasSsoAuthorizedClientRepository.class);
                var registration = context.getBean(ClientRegistrationRepository.class)
                        .findByRegistrationId("las-sso");
                var authentication = authentication();
                var a = loginFixture(context, repository, registration, authentication, "A", true);
                var start = new CyclicBarrier(2);
                try (var executor = Executors.newFixedThreadPool(2)) {
                    var first = executor.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return perform(context, a, "GET", "/api/private").getStatus();
                    });
                    var second = executor.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return perform(context, a, "GET", "/api/private").getStatus();
                    });
                    assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
                    assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(200);
                }
                assertThat(endpoint.refreshTokens).containsExactly("refresh-A");
                assertThat(a.isInvalid()).isFalse();
                assertThat(load(context, repository, a, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("new-refresh-A");
            });
        }
    }

    @Test
    void separateSessionsRefreshConcurrentlyThroughHttpWithoutSharingRotatedTokens() throws Exception {
        try (var endpoint = new TokenEndpoint()) {
            endpoint.concurrentRefreshes = new CountDownLatch(2);
            runner(endpoint, new RecordingSink()).run(context -> {
                assertThat(context).hasNotFailed();
                var repository = context.getBean(LasSsoAuthorizedClientRepository.class);
                var registration = context.getBean(ClientRegistrationRepository.class)
                        .findByRegistrationId("las-sso");
                var authentication = authentication();
                var a = loginFixture(context, repository, registration, authentication, "A", true);
                var b = loginFixture(context, repository, registration, authentication, "B", true);
                try (var executor = Executors.newFixedThreadPool(2)) {
                    var first = executor.submit(() -> perform(context, a, "GET", "/api/private").getStatus());
                    var second = executor.submit(() -> perform(context, b, "GET", "/api/private").getStatus());
                    assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
                    assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(200);
                }
                assertThat(endpoint.refreshTokens).containsExactlyInAnyOrder("refresh-A", "refresh-B");
                assertThat(load(context, repository, a, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("new-refresh-A");
                assertThat(load(context, repository, b, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("new-refresh-B");
            });
        }
    }

    @Test
    void temporarilyUnavailableTokenEndpointKeepsSessionAndOriginalTokenForRetry() throws Exception {
        try (var endpoint = new TokenEndpoint()) {
            endpoint.unavailable = true;
            runner(endpoint, new RecordingSink()).run(context -> {
                assertThat(context).hasNotFailed();
                var repository = context.getBean(LasSsoAuthorizedClientRepository.class);
                var registration = context.getBean(ClientRegistrationRepository.class)
                        .findByRegistrationId("las-sso");
                var authentication = authentication();
                var a = loginFixture(context, repository, registration, authentication, "A", true);

                var unavailable = perform(context, a, "GET", "/api/private");

                assertThat(unavailable.getStatus()).isEqualTo(503);
                assertThat(unavailable.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(unavailable.getContentAsString()).contains("SSO_UNAVAILABLE");
                assertThat(a.isInvalid()).isFalse();
                assertThat(load(context, repository, a, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("refresh-A");
                endpoint.unavailable = false;
                assertThat(perform(context, a, "GET", "/api/private").getStatus()).isEqualTo(200);
                assertThat(endpoint.refreshTokens).containsExactly("refresh-A", "refresh-A");
                assertThat(load(context, repository, a, authentication).getRefreshToken().getTokenValue())
                        .isEqualTo("new-refresh-A");
            });
        }
    }

    @Test
    void customRepositoryHandlesActualManagerSavesLoadsAndLogoutRemoval() throws Exception {
        try (var endpoint = new TokenEndpoint()) {
            var repository = new RecordingRepository();
            var sink = new RecordingSink();
            runner(endpoint, sink).withBean(LasSsoAuthorizedClientRepository.class, () -> repository)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(LasSsoAuthorizedClientRepository.class))
                                .hasSize(1).containsValue(repository);
                        var registration = context.getBean(ClientRegistrationRepository.class)
                                .findByRegistrationId("las-sso");
                        var authentication = authentication();
                        var a = loginFixture(context, repository, registration, authentication, "A", true);
                        var b = loginFixture(context, repository, registration, authentication, "B", true);

                        assertThat(perform(context, a, "GET", "/api/private").getStatus()).isEqualTo(200);
                        assertThat(perform(context, b, "GET", "/api/private").getStatus()).isEqualTo(200);
                        assertThat(repository.saves).hasValue(4); // two fixtures, two real refresh saves
                        sink.beforeSubmit = () -> {
                            assertThat(repository.removals).hasValue(1);
                            assertThat(a.isInvalid()).isTrue();
                        };
                        assertThat(logout(context, a).getStatus()).isEqualTo(302);
                        assertThat(repository.loads.get()).isGreaterThanOrEqualTo(4);
                        assertThat(repository.removals).hasValue(1);
                        assertThat(sink.tokens).containsExactly("new-refresh-A");
                        assertThat(load(context, repository, b, authentication).getRefreshToken().getTokenValue())
                                .isEqualTo("new-refresh-B");
                    });
        }
    }

    private static WebApplicationContextRunner runner(TokenEndpoint endpoint, RecordingSink sink) {
        var registration = ClientRegistration.withRegistrationId("las-sso")
                .clientId("test-client")
                .clientSecret("test-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://client.example/login/oauth2/code/las-sso")
                .scope("openid", "profile")
                .authorizationUri("https://issuer.example/oauth2/authorize")
                .tokenUri(endpoint.uri())
                .jwkSetUri("https://issuer.example/oauth2/jwks")
                .issuerUri("https://issuer.example")
                .userInfoUri("https://issuer.example/userinfo")
                .userNameAttributeName("sub")
                .build();
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LasSsoClientAutoConfiguration.class))
                .withUserConfiguration(SecurityConfiguration.class)
                .withBean(ClientRegistrationRepository.class,
                        () -> new InMemoryClientRegistrationRepository(registration))
                .withBean("lasSsoLogoutTokenDecoder", JwtDecoder.class,
                        () -> token -> { throw new UnsupportedOperationException("Logout JWT not exercised here"); })
                .withBean(LasSsoRevocationTaskSink.class, () -> sink);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class SecurityConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherRepository {
        @Bean
        @Primary
        UnrelatedRepository unrelatedRepository() {
            return new UnrelatedRepository();
        }
    }

    private static OAuth2AuthenticationToken authentication() {
        var now = Instant.now();
        var token = new OidcIdToken("fixture-id-token", now, now.plusSeconds(600), Map.of(
                "iss", "https://issuer.example", "aud", List.of("test-client"),
                "sub", "same-subject", "sid", "fixture-sid", "auth_time", now.getEpochSecond()));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(), token, "sub"),
                List.of(), "las-sso");
    }

    private static MockHttpSession loginFixture(
            WebApplicationContext context, LasSsoAuthorizedClientRepository repository,
            ClientRegistration registration, Authentication authentication, String suffix, boolean expired) {
        var session = new MockHttpSession(context.getServletContext());
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(authentication);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        var request = request(context, session, "GET", "/api/private");
        var now = Instant.now();
        repository.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, authentication.getName(),
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-" + suffix,
                        now.minusSeconds(900), expired ? now.minusSeconds(60) : now.plusSeconds(900)),
                new OAuth2RefreshToken("refresh-" + suffix, now.minusSeconds(900))),
                authentication, request, new MockHttpServletResponse());
        return session;
    }

    private static OAuth2AuthorizedClient load(WebApplicationContext context,
            LasSsoAuthorizedClientRepository repository, MockHttpSession session, Authentication authentication) {
        return repository.loadAuthorizedClient("las-sso", authentication,
                request(context, session, "GET", "/api/private"));
    }

    private static MockHttpServletRequest request(
            WebApplicationContext context, MockHttpSession session, String method, String path) {
        var request = new MockHttpServletRequest(context.getServletContext(), method, path);
        request.setServletPath(path);
        request.setSession(session);
        return request;
    }

    private static MockHttpServletResponse perform(
            WebApplicationContext context, MockHttpSession session, String method, String path) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        context.getBean(FilterChainProxy.class).doFilter(request(context, session, method, path), response, chain);
        if (response.getStatus() == 200) assertThat(chain.getRequest()).isNotNull();
        return response;
    }

    private static MockHttpServletResponse logout(WebApplicationContext context, MockHttpSession session)
            throws Exception {
        var probe = request(context, session, "GET", "/api/private");
        context.getBean(FilterChainProxy.class).doFilter(probe, new MockHttpServletResponse(), new MockFilterChain());
        var csrf = (CsrfToken) probe.getAttribute(CsrfToken.class.getName());
        var logout = request(context, session, "POST", "/api/auth/logout");
        logout.addParameter(csrf.getParameterName(), csrf.getToken());
        var response = new MockHttpServletResponse();
        context.getBean(FilterChainProxy.class).doFilter(logout, response, new MockFilterChain());
        return response;
    }

    private static final class RecordingSink implements LasSsoRevocationTaskSink {
        final List<String> tokens = new ArrayList<>();
        final List<String> hints = new ArrayList<>();
        Runnable beforeSubmit = () -> { };
        int attempts;

        @Override
        public void submit(ClientRegistration registration, String token, String tokenTypeHint) {
            attempts++;
            beforeSubmit.run();
            tokens.add(token);
            hints.add(tokenTypeHint);
        }
    }

    private static final class UnrelatedRepository implements OAuth2AuthorizedClientRepository {
        int calls;

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String registrationId, Authentication principal, HttpServletRequest request) {
            calls++;
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            calls++;
        }

        @Override
        public void removeAuthorizedClient(String registrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            calls++;
        }
    }

    private static final class RecordingRepository implements LasSsoAuthorizedClientRepository {
        final DefaultLasSsoAuthorizedClientRepository delegate = new DefaultLasSsoAuthorizedClientRepository();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger removals = new AtomicInteger();

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String id, Authentication principal, HttpServletRequest request) {
            loads.incrementAndGet();
            return delegate.loadAuthorizedClient(id, principal, request);
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            saves.incrementAndGet();
            delegate.saveAuthorizedClient(client, principal, request, response);
        }

        @Override
        public void removeAuthorizedClient(String id, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            removals.incrementAndGet();
            delegate.removeAuthorizedClient(id, principal, request, response);
        }
    }

    private static final class TokenEndpoint implements AutoCloseable {
        final HttpServer server;
        final List<String> refreshTokens = new CopyOnWriteArrayList<>();
        final Set<String> consumed = ConcurrentHashMap.newKeySet();
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        volatile String rejectedToken;
        volatile boolean unavailable;
        volatile CountDownLatch concurrentRefreshes;

        TokenEndpoint() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/oauth2/token", this::respond);
            server.setExecutor(executor);
            server.start();
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/token";
        }

        private void respond(HttpExchange exchange) throws IOException {
            try (exchange) {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String token = java.util.Arrays.stream(form.split("&"))
                        .filter(field -> field.startsWith("refresh_token="))
                        .map(field -> URLDecoder.decode(field.substring(14), StandardCharsets.UTF_8))
                        .findFirst().orElse("");
                String auth = "Basic " + Base64.getEncoder().encodeToString(
                        "test-client:test-secret".getBytes(StandardCharsets.UTF_8));
                boolean validRequest = "POST".equals(exchange.getRequestMethod())
                        && auth.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        && form.contains("grant_type=refresh_token") && token.startsWith("refresh-");
                int status;
                String body;
                if (!validRequest) {
                    status = 400;
                    body = "{\"error\":\"invalid_request\"}";
                } else {
                    refreshTokens.add(token);
                    var barrier = concurrentRefreshes;
                    boolean concurrent = true;
                    if (barrier != null) {
                        barrier.countDown();
                        try {
                            concurrent = barrier.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            concurrent = false;
                        }
                    }
                    status = unavailable || !concurrent ? 503
                            : token.equals(rejectedToken) || !consumed.add(token) ? 400 : 200;
                    String suffix = token.substring("refresh-".length());
                    body = status == 503 ? "{\"error\":\"temporarily_unavailable\"}"
                            : status == 400 ? "{\"error\":\"invalid_grant\"}" :
                            "{\"access_token\":\"new-access-" + suffix +
                            "\",\"refresh_token\":\"new-refresh-" + suffix +
                            "\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"openid profile\"}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.close();
        }
    }
}
