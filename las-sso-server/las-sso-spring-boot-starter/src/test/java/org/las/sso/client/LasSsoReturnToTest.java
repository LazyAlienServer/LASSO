package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class LasSsoReturnToTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/",
                "/projects/42",
                "/projects/42?tab=members&filter=active",
                "/search?q=中文",
                "/项目/42",
                "/users/name?tag=a%2Bb",
                "/search?q=https%3A%2F%2Fexample.com"
            })
    void safeLocalTargetsRoundTripThroughTheLoginEntryWithoutLosingQueryStructure(String target) {
        var request = new MockHttpServletRequest("GET", "/api/auth/login");
        request.addParameter("returnTo", target);
        var result = controller().login(request);

        assertThat(result.getStatusCode().value()).isEqualTo(302);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        String encodedTarget =
                result.getHeaders().getLocation().getRawQuery().substring("returnTo=".length());
        assertThat(URLDecoder.decode(encodedTarget, StandardCharsets.UTF_8))
                .isEqualTo(LasSsoReturnTo.validate(target));
        assertThat(request.getSession(false)).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "https://evil.example/path",
                "http://localhost/path",
                "//evil.example",
                "///evil.example",
                "javascript:alert(1)",
                "projects/42",
                "\\evil.example",
                "/\\evil.example",
                "/a\\b",
                "/%2Fevil.example",
                "/%5Cevil.example",
                "/%252Fevil.example",
                "/%255Cevil.example",
                "/%0d%0aLocation:evil",
                "/%250d%250aevil",
                "/a\r\nLocation:evil",
                "/a?x=%0d%0aevil",
                "/a#fragment",
                "/./evil",
                "/a/../evil",
                "/%2e%2e/evil",
                "/a/%2E/evil",
                "/a;..//evil",
                "/bad%",
                "/a b",
                " /a",
                "/a\t",
                "/a?x=\\evil",
                "/a%20b",
                "/a?x=%250d%250aevil"
            })
    void unsafeTargetsAreRejectedWithNoStoreAndWithoutCreatingSession(String target) {
        var request = new MockHttpServletRequest("GET", "/api/auth/login");
        request.addParameter("returnTo", target);

        var result = controller().login(request);

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        assertThat(result.getHeaders().getLocation()).isNull();
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void absentTargetRetainsDefaultLoginAndRepeatedOrOversizedValuesAreRejected() {
        var request = new MockHttpServletRequest("GET", "/api/auth/login");
        assertThat(controller().login(request).getHeaders().getLocation())
                .isEqualTo(URI.create("/oauth2/authorization/las-sso"));
        request.addParameter("returnTo", "/a", "/b");
        assertThat(controller().login(request).getStatusCode().value()).isEqualTo(400);
        request.setParameter("returnTo", "/" + "a".repeat(2048));
        assertThat(controller().login(request).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void entryRedirectAndFinalTargetStayInsideApplicationContext() throws Exception {
        var request = new MockHttpServletRequest("GET", "/bff/api/auth/login");
        request.setContextPath("/bff");
        request.addParameter("returnTo", "/projects/42");
        assertThat(controller().login(request).getHeaders().getLocation().getPath())
                .isEqualTo("/bff/oauth2/authorization/las-sso");
        request.setAttribute(LasSsoReturnTo.CONSUMED_ATTRIBUTE, "/projects/42");
        LasSsoReturnTo.verified(request);
        var response = new MockHttpServletResponse();
        new LasSsoReturnToSuccessHandler(
                        (req, res, auth) -> {
                            throw new AssertionError("Unexpected fallback");
                        })
                .onAuthenticationSuccess(request, response, null);
        assertThat(response.getRedirectedUrl()).isEqualTo("/bff/projects/42");
    }

    @Test
    void callbackQueryDoesNotBecomeAVerifiedTarget() {
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/las-sso");
        request.addParameter("returnTo", "//evil.example");
        LasSsoReturnTo.verified(request);
        assertThat(LasSsoReturnTo.target(request)).isEmpty();
    }

    @Test
    void transactionExpiresExactlyAtTenMinutesAndFutureIssueTimeIsRejected() {
        Instant issued = Instant.parse("2026-10-04T00:00:00Z");
        var now = new AtomicReference<>(issued);
        var repository = new LasSsoAuthorizationRequestRepository(clock(now));
        var session = new MockHttpSession();
        var callback = callback(session, "state-one");
        var response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(
                authorization("state-one", "/one", issued), callback, response);
        now.set(issued.plusSeconds(599));
        assertThat(repository.loadAuthorizationRequest(callback)).isNotNull();
        now.set(issued.plusSeconds(600));
        assertThat(repository.removeAuthorizationRequest(callback, response)).isNull();
        assertThat(callback.getAttribute(LasSsoReturnTo.CONSUMED_ATTRIBUTE)).isNull();
        assertThat(repository.loadAuthorizationRequest(callback)).isNull();
        now.set(issued.minusSeconds(1));
        repository.saveAuthorizationRequest(
                authorization("state-one", "/one", issued), callback, response);
        assertThat(repository.removeAuthorizationRequest(callback, response)).isNull();
    }

    @Test
    void wrongOrDuplicateStateDoesNotConsumeTheMatchingTransaction() {
        Instant now = Instant.now();
        var repository = new LasSsoAuthorizationRequestRepository(Clock.systemUTC());
        var session = new MockHttpSession();
        var valid = callback(session, "correct");
        var response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorization("correct", "/one", now), valid, response);
        assertThat(repository.removeAuthorizationRequest(callback(session, "incorrect"), response))
                .isNull();
        var duplicate = callback(session, "correct");
        duplicate.addParameter("state", "correct");
        assertThat(repository.removeAuthorizationRequest(duplicate, response)).isNull();
        assertThat(repository.removeAuthorizationRequest(valid, response)).isNotNull();
        assertThat(valid.getAttribute(LasSsoReturnTo.CONSUMED_ATTRIBUTE)).isEqualTo("/one");
        assertThat(repository.removeAuthorizationRequest(valid, response)).isNull();
        assertThat(valid.getAttribute(LasSsoReturnTo.CONSUMED_ATTRIBUTE)).isNull();
    }

    @Test
    void sameSessionNewAuthorizationReplacesOldTargetAndOtherSessionRemainsIndependent() {
        var repository = new LasSsoAuthorizationRequestRepository(Clock.systemUTC());
        var a = new MockHttpSession();
        var b = new MockHttpSession();
        var response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(
                authorization("old", "/old", Instant.now()), callback(a, "old"), response);
        repository.saveAuthorizationRequest(
                authorization("new", "/new", Instant.now()), callback(a, "new"), response);
        repository.saveAuthorizationRequest(
                authorization("other", "/other", Instant.now()), callback(b, "other"), response);

        assertThat(repository.removeAuthorizationRequest(callback(a, "old"), response)).isNull();
        var latest = callback(a, "new");
        assertThat(repository.removeAuthorizationRequest(latest, response)).isNotNull();
        assertThat(latest.getAttribute(LasSsoReturnTo.CONSUMED_ATTRIBUTE)).isEqualTo("/new");
        var independent = callback(b, "other");
        assertThat(repository.removeAuthorizationRequest(independent, response)).isNotNull();
        assertThat(independent.getAttribute(LasSsoReturnTo.CONSUMED_ATTRIBUTE)).isEqualTo("/other");
    }

    @Test
    void concurrentCallbacksConsumeTheSessionTransactionOnlyOnce() throws Exception {
        var repository = new LasSsoAuthorizationRequestRepository(Clock.systemUTC());
        var session = new MockHttpSession();
        repository.saveAuthorizationRequest(
                authorization("state", "/one", Instant.now()),
                callback(session, "state"),
                new MockHttpServletResponse());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks =
                    List.of(
                            executor.submit(
                                    () -> {
                                        barrier.await(5, TimeUnit.SECONDS);
                                        return repository.removeAuthorizationRequest(
                                                callback(session, "state"),
                                                new MockHttpServletResponse());
                                    }),
                            executor.submit(
                                    () -> {
                                        barrier.await(5, TimeUnit.SECONDS);
                                        return repository.removeAuthorizationRequest(
                                                callback(session, "state"),
                                                new MockHttpServletResponse());
                                    }));
            int successes = 0;
            for (var task : tasks) if (task.get(5, TimeUnit.SECONDS) != null) successes++;
            assertThat(successes).isEqualTo(1);
        }
    }

    private static LasSsoSessionController controller() {
        return new LasSsoSessionController(new LasSsoClientProperties("las-sso", null));
    }

    private static OAuth2AuthorizationRequest authorization(
            String state, String target, Instant created) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .clientId("client")
                .authorizationUri("https://issuer.example/authorize")
                .redirectUri("https://client.example/callback")
                .state(state)
                .attributes(
                        attributes -> {
                            attributes.put(LasSsoReturnTo.AUTH_ATTRIBUTE, target);
                            attributes.put(
                                    LasSsoAuthorizationRequestResolver.CREATED_ATTRIBUTE, created);
                        })
                .build();
    }

    private static MockHttpServletRequest callback(MockHttpSession session, String state) {
        var request = new MockHttpServletRequest("GET", "/login/oauth2/code/las-sso");
        request.setSession(session);
        request.addParameter("state", state);
        return request;
    }

    private static Clock clock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }
}
