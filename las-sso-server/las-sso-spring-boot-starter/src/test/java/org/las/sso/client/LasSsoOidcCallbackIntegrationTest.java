package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.consumer.ApplicationOwnedSecurityConfiguration;
import org.las.sso.consumer.ReturnToSecurityConfiguration;
import org.las.sso.consumer.PasswordEvidenceSecurityConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.context.WebApplicationContext;

/** Real OAuth2 Login filter, Code/PKCE exchange and RSA/JWKS validation; inbound Servlet is a fixture. */
class LasSsoOidcCallbackIntegrationTest {

    private static final String SUBJECT = "019fb42f-5f16-7c31-8ce0-9e54f7441ff0";
    private static final String CALLBACK = "http://localhost/login/oauth2/code/las-sso";

    @Test
    void publicAuthorizationCustomizerAddsReauthenticationParametersAndKeepsPkceTargetAndSignedEvidence() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).withUserConfiguration(PasswordEvidenceSecurityConfiguration.class).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/protected?tab=security", null);
                assertThat(transaction.parameters()).containsEntry("prompt", "login").containsEntry("max_age", "0");
                assertThat(transaction.parameters().get("state")).isNotBlank();
                assertThat(transaction.parameters().get("nonce")).isNotBlank();
                assertThat(transaction.parameters().get("code_challenge")).isNotBlank();
                assertThat(transaction.parameters()).doesNotContainKeys("returnTo", "auth_time");
                var response = perform(context, returnCallback(context, op, transaction, "sid-password-evidence"));
                assertThat(response.getRedirectedUrl()).isEqualTo("/protected?tab=security");
                assertThat(response.getHeader("X-Consumer-Success")).isEqualTo("checked");
                assertThat(response.getHeader("X-Fixture-Auth-Time")).isNotBlank();
                var user = (org.springframework.security.oauth2.core.oidc.user.OidcUser)
                        ((org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken) authentication(transaction.session())).getPrincipal();
                assertThat(user.getIdToken().getAuthenticatedAt()).isNotNull();
                assertThat(backChannel(context, op.logoutToken("sid-password-evidence")).getStatus()).isEqualTo(204);
                assertThat(transaction.session().isInvalid()).isTrue();
            });
        }
    }

    @Test
    void defaultLoginDoesNotBlindlyForwardBrowserSuppliedProtocolParameters() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                var request = request(context, null, "GET", "/oauth2/authorization/las-sso");
                request.addParameter("prompt", "login"); request.addParameter("max_age", "0"); request.addParameter("auth_time", "9999999999");
                var response = perform(context, request);
                var parameters = form(URI.create(response.getRedirectedUrl()).getRawQuery());
                assertThat(parameters).doesNotContainKeys("prompt", "max_age", "auth_time");
                assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/projects/42", "/projects/42?tab=members&filter=active"})
    void defaultSignedLoginReturnsToTheValidatedOriginalTarget(String target) throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var start = request(context, null, "GET", "/api/auth/login");
                start.addParameter("returnTo", target);
                var entry = perform(context, start);
                assertThat(entry.getStatus()).isEqualTo(302);
                var authorization = request(context, null, "GET", "/oauth2/authorization/las-sso");
                String query = URI.create(entry.getRedirectedUrl()).getRawQuery();
                if (query != null) form(query).forEach(authorization::addParameter);
                var redirect = perform(context, authorization);
                assertThat(redirect.getStatus()).isEqualTo(302);
                var parameters = form(URI.create(redirect.getRedirectedUrl()).getRawQuery());
                var session = (MockHttpSession) authorization.getSession(false);
                var callback = request(context, session, "GET", "/login/oauth2/code/las-sso");
                callback.addParameter("code", op.issueCode(parameters, "sid-return"));
                callback.addParameter("state", parameters.get("state"));

                var response = perform(context, callback);

                assertThat(response.getRedirectedUrl()).isEqualTo(target);
                assertThat(backChannel(context, op.logoutToken("sid-return")).getStatus()).isEqualTo(204);
                assertThat(session.isInvalid()).isTrue();
            });
        }
    }

    @Test
    void callbackTargetCannotOverrideStoredTargetAndStateCannotBeReplayed() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/original?tab=one", null);
                var callback = returnCallback(context, op, transaction, "sid-bound");
                callback.addParameter("returnTo", "//evil.example");
                assertThat(perform(context, callback).getRedirectedUrl()).isEqualTo("/original?tab=one");
                assertThat(op.exchanges).hasValue(1);
                var replay = request(context, transaction.session(), "GET", "/login/oauth2/code/las-sso");
                replay.addParameter("code", callback.getParameter("code"));
                replay.addParameter("state", transaction.parameters().get("state"));
                replay.addParameter("returnTo", "/other");
                assertThat(perform(context, replay).getRedirectedUrl()).contains("error");
                assertThat(op.exchanges).hasValue(1);
            });
        }
    }

    @Test
    void separateBrowsersAndLatestAuthorizationKeepTheirOwnTargets() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var old = beginReturn(context, "/old", null);
                var latest = beginReturn(context, "/latest", old.session());
                var other = beginReturn(context, "/other", null);

                assertThat(perform(context, returnCallback(context, op, old, "sid-old-target")).getRedirectedUrl())
                        .contains("error");
                assertThat(op.exchanges).hasValue(0);
                assertThat(perform(context, returnCallback(context, op, other, "sid-other-target")).getRedirectedUrl())
                        .isEqualTo("/other");
                assertThat(perform(context, returnCallback(context, op, latest, "sid-latest-target")).getRedirectedUrl())
                        .isEqualTo("/latest");
                assertThat(op.exchanges).hasValue(2);
            });
        }
    }

    @Test
    void failedNonceConsumesTransactionWithoutReturningTargetOnThisOrLaterLogin() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/sensitive-target", null);
                op.invalid = OidcEndpoint.InvalidToken.NONCE;
                var callback = returnCallback(context, op, transaction, "sid-nonce-target");
                assertThat(perform(context, callback).getRedirectedUrl()).contains("error");
                assertThat(LasSsoReturnTo.target(callback)).isEmpty();
                op.invalid = null;
                var next = login(context, op, "sid-next-target", transaction.session());
                assertThat(next.response().getRedirectedUrl()).isEqualTo("/");
                assertThat(backChannel(context, op.logoutToken("sid-next-target")).getStatus()).isEqualTo(204);
            });
        }
    }

    @Test
    void wrongBrowserCallbackCannotConsumeAnotherBrowsersTarget() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/browser-a", null);
                var wrong = request(context, new MockHttpSession(), "GET", "/login/oauth2/code/las-sso");
                wrong.addParameter("code", op.issueCode(transaction.parameters(), "sid-browser-a"));
                wrong.addParameter("state", transaction.parameters().get("state"));
                assertThat(perform(context, wrong).getRedirectedUrl()).contains("error");
                assertThat(op.exchanges).hasValue(0);
                assertThat(perform(context, returnCallback(context, op, transaction, "sid-browser-a")).getRedirectedUrl())
                        .isEqualTo("/browser-a");
            });
        }
    }

    @Test
    void customSuccessDelegateIsPreservedUnlessItExplicitlyUsesVerifiedReturnTarget() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).withUserConfiguration(ApplicationOwnedSecurityConfiguration.class).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/requested", null);
                var response = perform(context, returnCallback(context, op, transaction, "sid-custom-preserved"));
                assertThat(response.getRedirectedUrl()).isEqualTo("/app-success");
                assertThat(response.getHeader("X-Application-Success")).isEqualTo("called");
            });
            runner(op).withUserConfiguration(ReturnToSecurityConfiguration.class).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/requested", null);
                var response = perform(context, returnCallback(context, op, transaction, "sid-custom-optin"));
                assertThat(response.getRedirectedUrl()).isEqualTo("/requested");
                assertThat(response.getHeader("X-Business-Success")).isEqualTo("checked");
                assertThat(response.getHeader("X-Verified-Target")).isEqualTo("/requested");
                assertThat(backChannel(context, op.logoutToken("sid-custom-optin")).getStatus()).isEqualTo(204);
            });
        }
    }

    @Test
    void invalidOrDuplicatedTargetsDoNotStartAnAuthorizationAndDoNotPoisonValidTransaction() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var valid = beginReturn(context, "/valid", null);
                var invalid = request(context, valid.session(), "GET", "/api/auth/login");
                invalid.addParameter("returnTo", "//evil.example");
                assertThat(perform(context, invalid).getStatus()).isEqualTo(400);
                var duplicate = request(context, null, "GET", "/api/auth/login");
                duplicate.addParameter("returnTo", "/one", "/two");
                assertThat(perform(context, duplicate).getStatus()).isEqualTo(400);
                var direct = request(context, valid.session(), "GET", "/oauth2/authorization/las-sso");
                direct.addParameter("returnTo", "/%2fevil.example");
                var rejected = perform(context, direct);
                assertThat(rejected.getStatus()).isEqualTo(400);
                assertThat(rejected.getRedirectedUrl()).isNull();
                assertThat(op.exchanges).hasValue(0);
                assertThat(perform(context, returnCallback(context, op, valid, "sid-valid-after-invalid"))
                        .getRedirectedUrl()).isEqualTo("/valid");
            });
        }
    }

    @Test
    void expiredSignedFlowIsRejectedBeforeCodeExchangeAndCannotReuseTarget() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/expired-target", null);
                org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest stored = null;
                var names = transaction.session().getAttributeNames();
                while (names.hasMoreElements()) {
                    Object value = transaction.session().getAttribute(names.nextElement());
                    if (value instanceof org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest auth) {
                        stored = auth;
                    }
                }
                assertThat(stored).isNotNull();
                var expired = org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.from(stored)
                        .attributes(attributes -> attributes.put(LasSsoAuthorizationRequestResolver.CREATED_ATTRIBUTE,
                                Instant.now().minusSeconds(601))).build();
                var callback = returnCallback(context, op, transaction, "sid-expired-target");
                new LasSsoAuthorizationRequestRepository(java.time.Clock.systemUTC())
                        .saveAuthorizationRequest(expired, callback, new MockHttpServletResponse());

                assertThat(perform(context, callback).getRedirectedUrl()).contains("error");
                assertThat(LasSsoReturnTo.target(callback)).isEmpty();
                assertThat(op.exchanges).hasValue(0);
                assertThat(login(context, op, "sid-after-expired", transaction.session()).response().getRedirectedUrl())
                        .isEqualTo("/");
            });
        }
    }

    @Test
    void duplicateCallbackStateIsRejectedWithoutConsumingValidTarget() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/valid-state", null);
                var duplicate = returnCallback(context, op, transaction, "sid-duplicate-state");
                duplicate.addParameter("state", transaction.parameters().get("state"));
                assertThat(perform(context, duplicate).getRedirectedUrl()).contains("error");
                assertThat(op.exchanges).hasValue(0);
                assertThat(perform(context, returnCallback(context, op, transaction, "sid-valid-state"))
                        .getRedirectedUrl()).isEqualTo("/valid-state");
            });
        }
    }

    @Test
    void authorizationErrorConsumesReturnTransactionWithoutExposingTarget() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/cancelled-target", null);
                var rejected = request(context, transaction.session(), "GET", "/login/oauth2/code/las-sso");
                rejected.addParameter("state", transaction.parameters().get("state"));
                rejected.addParameter("error", "access_denied");
                assertThat(perform(context, rejected).getRedirectedUrl()).contains("error");
                assertThat(LasSsoReturnTo.target(rejected)).isEmpty();
                assertThat(perform(context, returnCallback(context, op, transaction, "sid-cancelled-target"))
                        .getRedirectedUrl()).contains("error");
                assertThat(op.exchanges).hasValue(0);
            });
        }
    }

    @Test
    void concurrentSignedCallbacksExchangeTheCodeAndUseItsTargetOnlyOnce() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/single-target", null);
                String code = op.issueCode(transaction.parameters(), "sid-concurrent-target");
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var responses = new CopyOnWriteArrayList<String>();
                    var calls = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                        var callback = request(context, transaction.session(), "GET", "/login/oauth2/code/las-sso");
                        callback.addParameter("code", code);
                        callback.addParameter("state", transaction.parameters().get("state"));
                        barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        responses.add(perform(context, callback).getRedirectedUrl());
                        return true;
                    })).toList();
                    for (var call : calls) assertThat(call.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    assertThat(responses.stream().filter("/single-target"::equals).count()).isEqualTo(1);
                    assertThat(responses.stream().filter(value -> value != null && value.contains("error")).count())
                            .isEqualTo(1);
                    assertThat(op.exchanges).hasValue(1);
                }
            });
        }
    }

    @Test
    void verifiedTargetDoesNotBypassAnApplicationSuccessCheckThatStopsRedirecting() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).withUserConfiguration(ReturnToSecurityConfiguration.class).run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/blocked", null);
                var response = perform(context, returnCallback(context, op, transaction, "sid-blocked-target"));
                assertThat(response.getStatus()).isEqualTo(403);
                assertThat(response.getHeader("X-Business-Success")).isEqualTo("checked");
                assertThat(response.getRedirectedUrl()).isNull();
            });
        }
    }

    @Test
    void failedSessionRegistrationDoesNotExposeTheConsumedTargetToSuccessCode() throws Exception {
        try (var op = new OidcEndpoint()) {
            var sessions = new RecordingSessions();
            sessions.fail = true;
            runner(op).withBean(LasSsoBackChannelSessionStore.class, () -> sessions)
                    .withBean(LasSsoRevocationTaskSink.class, () -> (registration, token, hint) -> { })
                    .run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/registration-target", null);
                var callback = returnCallback(context, op, transaction, "sid-failed-return");
                var response = perform(context, callback);
                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(response.getRedirectedUrl()).isNull();
                assertThat(LasSsoReturnTo.target(callback)).isEmpty();
                assertThat(transaction.session().isInvalid()).isTrue();
            });
        }
    }

    @Test
    void signedCallbackRegistersBeforeAnySubsequentRequestAndImmediateLogoutFindsOnlyItsSession()
            throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                assertThat(context).hasNotFailed();
                var a = login(context, op, "sid-A");
                var b = login(context, op, "sid-B");

                assertThat(a.response().getRedirectedUrl()).isEqualTo("/");
                assertThat(b.response().getRedirectedUrl()).isEqualTo("/");
                assertThat(a.session().getId()).isNotEqualTo(a.preLoginSessionId());
                assertThat(authentication(a.session()).getName()).isEqualTo(SUBJECT);
                assertThat(load(context, a).getRefreshToken().getTokenValue()).isEqualTo("refresh-sid-A");
                assertThat(op.exchanges).hasValue(2);
                assertThat(op.jwksRequests.get()).isPositive();

                // No GET after either callback. Execute receiver through the security chain immediately.
                var logout = backChannel(context, op.logoutToken("sid-A"));

                assertThat(logout.getStatus()).isEqualTo(204);
                assertThat(a.session().isInvalid()).isTrue();
                assertThat(b.session().isInvalid()).isFalse();
                assertThat(load(context, b).getRefreshToken().getTokenValue()).isEqualTo("refresh-sid-B");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedLogoutBeforeCallbackRejectsLateSessionWithoutCallingApplicationSuccess(boolean applicationOwned)
            throws Exception {
        try (var op = new OidcEndpoint()) {
            var tokens = new RecordingTokens();
            var submitted = new CopyOnWriteArrayList<String>();
            var configured = runner(op)
                    .withBean(LasSsoAuthorizedClientRepository.class, () -> tokens)
                    .withBean(LasSsoRevocationTaskSink.class, () -> (registration, token, hint) -> submitted.add(token));
            if (applicationOwned) configured = configured.withUserConfiguration(ApplicationOwnedSecurityConfiguration.class);
            configured.run(context -> {
                assertThat(context).hasNotFailed();
                var transaction = beginReturn(context, "/late-target", null);
                var callback = returnCallback(context, op, transaction, "sid-before-callback");
                String logoutToken = op.logoutToken("sid-before-callback");
                assertThat(backChannel(context, logoutToken).getStatus()).isEqualTo(204);
                assertThat(backChannel(context, logoutToken).getStatus()).isEqualTo(204);

                var response = perform(context, callback);

                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(response.getRedirectedUrl()).isNull();
                assertThat(response.getHeader("X-Application-Success")).isNull();
                assertThat(response.getContentAsString()).contains("SESSION_REGISTRATION_FAILED");
                assertThat(LasSsoReturnTo.target(callback)).isEmpty();
                assertThat(transaction.session().isInvalid()).isTrue();
                assertThat(tokens.saves).hasValue(1);
                assertThat(tokens.removals).hasValue(1);
                assertThat(submitted).containsExactly("refresh-sid-before-callback");
                assertThat(login(context, op, "sid-new-login").response().getRedirectedUrl())
                        .isEqualTo(applicationOwned ? "/app-success" : "/");
            });
        }
    }

    @Test
    void applicationOwnedChainUsesPublicCompositionAndKeepsItsAuthorizationAndSuccessHandler() throws Exception {
        try (var op = new OidcEndpoint()) {
            var sessions = new RecordingSessions();
            var tokens = new RecordingTokens();
            runner(op).withUserConfiguration(ApplicationOwnedSecurityConfiguration.class)
                    .withBean(LasSsoBackChannelSessionStore.class, () -> sessions)
                    .withBean(LasSsoAuthorizedClientRepository.class, () -> tokens).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class))
                        .hasSize(1).containsKey("applicationChain");
                var login = login(context, op, "sid-custom");
                assertThat(login.response().getRedirectedUrl()).isEqualTo("/app-success");
                assertThat(login.response().getHeader("X-Application-Success")).isEqualTo("called");
                assertThat(sessions.registrations).containsExactly(login.session().getId());
                assertThat(tokens.saves).hasValue(1);
                assertThat(context.getBean(FilterChainProxy.class).getFilterChains().getFirst().getFilters()
                        .stream().filter(filter -> filter instanceof LasSsoAuthorizedClientRefreshFilter).count())
                        .isEqualTo(1);
                assertThat(context.getBean(FilterChainProxy.class).getFilterChains().getFirst().getFilters()
                        .stream().filter(filter -> filter.getClass().getSimpleName().equals("LasSsoSessionRegistrationFilter"))
                        .count()).isEqualTo(1);
                assertThat(perform(context, request(context, login.session(), "GET", "/app/admin")).getStatus())
                        .isEqualTo(403);
                assertThat(backChannel(context, op.logoutToken("sid-custom")).getStatus()).isEqualTo(204);
                assertThat(login.session().isInvalid()).isTrue();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedLogoutDuringCodeExchangeCannotBeLostBeforeCallbackRegistration(boolean applicationOwned)
            throws Exception {
        try (var op = new OidcEndpoint()) {
            var submitted = new CopyOnWriteArrayList<String>();
            var configured = runner(op)
                    .withBean(LasSsoRevocationTaskSink.class, () -> (registration, token, hint) -> submitted.add(token));
            if (applicationOwned) configured = configured.withUserConfiguration(ApplicationOwnedSecurityConfiguration.class);
            configured.run(context -> {
                assertThat(context).hasNotFailed();
                op.beforeTokenResponse = () -> {
                    try {
                        assertThat(backChannel(context, op.logoutToken("sid-in-flight-code")).getStatus()).isEqualTo(204);
                    } catch (Exception exception) { throw new IllegalStateException("Fixture signed logout failed", exception); }
                };
                var transaction = beginReturn(context, "/in-flight-target", null);
                var response = perform(context, returnCallback(context, op, transaction, "sid-in-flight-code"));
                assertThat(op.exchanges).hasValue(1);
                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(response.getRedirectedUrl()).isNull();
                assertThat(response.getHeader("X-Application-Success")).isNull();
                assertThat(transaction.session().isInvalid()).isTrue();
                assertThat(submitted).containsExactly("refresh-sid-in-flight-code");
            });
        }
    }

    @Test
    void invalidSignedLogoutCannotPoisonSidRegistration() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).run(context -> {
                var transaction = beginReturn(context, "/not-revoked", null);
                var callback = returnCallback(context, op, transaction, "sid-invalid-logout");
                String token = op.logoutToken("sid-invalid-logout");
                int signature = token.lastIndexOf('.') + 1;
                String tampered = token.substring(0, signature)
                        + (token.charAt(signature) == 'A' ? 'B' : 'A') + token.substring(signature + 1);
                assertThat(backChannel(context, tampered).getStatus()).isEqualTo(400);
                assertThat(perform(context, callback).getRedirectedUrl()).isEqualTo("/not-revoked");
                assertThat(transaction.session().isInvalid()).isFalse();
                assertThat(backChannel(context, token).getStatus()).isEqualTo(204);
                assertThat(transaction.session().isInvalid()).isTrue();
            });
        }
    }

    @Test
    void failedCallbackRegistrationEndsLocalLoginBeforeCustomErrorHandlerAndDoesNotRedirect() throws Exception {
        try (var op = new OidcEndpoint()) {
            var sessions = new RecordingSessions();
            sessions.fail = true;
            var tokens = new RecordingTokens();
            var submitted = new CopyOnWriteArrayList<String>();
            runner(op).withUserConfiguration(ApplicationOwnedSecurityConfiguration.class)
                    .withBean(LasSsoBackChannelSessionStore.class, () -> sessions)
                    .withBean(LasSsoAuthorizedClientRepository.class, () -> tokens)
                    .withBean(LasSsoRevocationTaskSink.class, () -> (registration, token, hint) -> submitted.add(token))
                    .withBean(LasSsoClientErrorHandler.class, () -> (request, response, failure) -> {
                        assertThat(failure).isEqualTo(LasSsoClientErrorHandler.Failure.SESSION_REGISTRATION_FAILED);
                        assertThat(request.getSession(false)).isNull();
                        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext()
                                .getAuthentication()).isNull();
                        assertThat(tokens.removals).hasValue(1);
                        response.setStatus(503);
                        response.setHeader("X-Application-Error", failure.name());
                    }).run(context -> {
                assertThat(context).hasNotFailed();
                var login = login(context, op, "sid-failed");
                assertThat(login.response().getStatus()).isEqualTo(503);
                assertThat(login.response().getRedirectedUrl()).isNull();
                assertThat(login.response().getHeader("X-Application-Success")).isNull();
                assertThat(login.response().getHeader("X-Application-Error")).isEqualTo("SESSION_REGISTRATION_FAILED");
                assertThat(login.session().isInvalid()).isTrue();
                assertThat(sessions.unregistered).contains(login.session().getId());
                assertThat(submitted).containsExactly("refresh-sid-failed");
            });
        }
    }

    @Test
    void registrationFailureOnAnAuthenticatedRequestStopsTheChainAndEndsItsSession() throws Exception {
        try (var op = new OidcEndpoint()) {
            var sessions = new RecordingSessions();
            var tokens = new RecordingTokens();
            var submitted = new CopyOnWriteArrayList<String>();
            runner(op).withBean(LasSsoBackChannelSessionStore.class, () -> sessions)
                    .withBean(LasSsoAuthorizedClientRepository.class, () -> tokens)
                    .withBean(LasSsoRevocationTaskSink.class, () -> (registration, token, hint) -> submitted.add(token))
                    .run(context -> {
                assertThat(context).hasNotFailed();
                var login = login(context, op, "sid-request-failed");
                sessions.fail = true;
                var response = perform(context, request(context, login.session(), "GET", "/api/private"));
                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(response.getContentAsString()).contains("SESSION_REGISTRATION_FAILED");
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(login.session().isInvalid()).isTrue();
                assertThat(tokens.removals).hasValue(1);
                assertThat(submitted).containsExactly("refresh-sid-request-failed");
            });
        }
    }

    @Test
    void invalidSignatureNeverRegistersOrSavesAuthorization() throws Exception {
        rejectToken(OidcEndpoint.InvalidToken.SIGNATURE);
    }

    @Test
    void mismatchedNonceNeverRegistersOrSavesAuthorization() throws Exception {
        rejectToken(OidcEndpoint.InvalidToken.NONCE);
    }

    @Test
    void verifiedIdTokenWithoutSidCannotCompleteLasSessionRegistration() throws Exception {
        try (var op = new OidcEndpoint()) {
            op.invalid = OidcEndpoint.InvalidToken.MISSING_SID;
            var sessions = new RecordingSessions();
            var tokens = new RecordingTokens();
            runner(op).withBean(LasSsoBackChannelSessionStore.class, () -> sessions)
                    .withBean(LasSsoAuthorizedClientRepository.class, () -> tokens)
                    .withBean(LasSsoRevocationTaskSink.class, () -> (registration, token, hint) -> { })
                    .run(context -> {
                assertThat(context).hasNotFailed();
                var login = login(context, op, "sid-missing");
                assertThat(login.response().getStatus()).isEqualTo(503);
                assertThat(login.response().getContentAsString()).contains("SESSION_REGISTRATION_FAILED");
                assertThat(login.session().isInvalid()).isTrue();
                assertThat(sessions.registrations).isEmpty();
                assertThat(tokens.saves).hasValue(1);
                assertThat(tokens.removals).hasValue(1);
            });
        }
    }

    private static void rejectToken(OidcEndpoint.InvalidToken invalid) throws Exception {
        try (var op = new OidcEndpoint()) {
            op.invalid = invalid;
            var sessions = new RecordingSessions();
            var tokens = new RecordingTokens();
            runner(op).withBean(LasSsoBackChannelSessionStore.class, () -> sessions)
                    .withBean(LasSsoAuthorizedClientRepository.class, () -> tokens).run(context -> {
                assertThat(context).hasNotFailed();
                var login = login(context, op, "sid-invalid");
                assertThat(login.response().getRedirectedUrl()).contains("error");
                assertThat(sessions.registrations).isEmpty();
                assertThat(tokens.saves).hasValue(0);
                assertThat(login.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                        .isNull();
            });
        }
    }

    @Test
    void sessionIdChangeMovesIndexAndDestructionUnregistersItWithoutAnotherRequest() throws Exception {
        try (var op = new OidcEndpoint()) {
            var sessions = new RecordingSessions();
            runner(op).withBean(LasSsoBackChannelSessionStore.class, () -> sessions).run(context -> {
                assertThat(context).hasNotFailed();
                var login = login(context, op, "sid-rotation");
                var listener = (LasSsoSessionLifecycleListener) context
                        .getBean("lasSsoSessionLifecycleListener", ServletListenerRegistrationBean.class).getListener();
                String old = login.session().getId();
                login.session().changeSessionId();
                listener.sessionIdChanged(new HttpSessionEvent(login.session()), old);
                assertThat(sessions.unregistered).contains(old);
                assertThat(sessions.registrations).containsExactly(old, login.session().getId());
                listener.sessionDestroyed(new HttpSessionEvent(login.session()));
                assertThat(sessions.invalidate("sid-rotation", SUBJECT)).isZero();
                assertThat(login.session().isInvalid()).isFalse();
                login.session().invalidate();
            });
        }
    }

    @Test
    void applicationOwnedChainCurrentLogoutIsCsrfProtectedAndUsesTheLoginRepository() throws Exception {
        try (var op = new OidcEndpoint()) {
            var tokens = new RecordingTokens();
            var submitted = new CopyOnWriteArrayList<String>();
            runner(op).withUserConfiguration(ApplicationOwnedSecurityConfiguration.class)
                    .withBean(LasSsoAuthorizedClientRepository.class, () -> tokens)
                    .withBean(LasSsoRevocationTaskSink.class, () -> (registration, token, hint) -> submitted.add(token))
                    .run(context -> {
                assertThat(context).hasNotFailed();
                var login = login(context, op, "sid-current");
                assertThat(perform(context, request(context, login.session(), "POST", "/api/auth/logout")).getStatus())
                        .isEqualTo(403);
                assertThat(login.session().isInvalid()).isFalse();
                var probe = request(context, login.session(), "GET", "/api/private");
                perform(context, probe);
                var csrf = (CsrfToken) probe.getAttribute(CsrfToken.class.getName());
                var logout = request(context, login.session(), "POST", "/api/auth/logout");
                logout.addParameter(csrf.getParameterName(), csrf.getToken());
                assertThat(perform(context, logout).getStatus()).isEqualTo(302);
                assertThat(login.session().isInvalid()).isTrue();
                assertThat(tokens.removals).hasValue(1);
                assertThat(submitted).containsExactly("refresh-sid-current");
            });
        }
    }

    @ParameterizedTest
    @EnumSource(RefreshFailure.class)
    void applicationOwnedChainRoutesActualHttpRefreshFailuresToCustomErrorHandler(RefreshFailure failure)
            throws Exception {
        try (var op = new OidcEndpoint()) {
            op.refreshFailure = failure;
            var handled = new CopyOnWriteArrayList<LasSsoClientErrorHandler.Failure>();
            runner(op).withUserConfiguration(ApplicationOwnedSecurityConfiguration.class)
                    .withBean(LasSsoClientErrorHandler.class, () -> (request, response, error) -> {
                        handled.add(error);
                        if (error == LasSsoClientErrorHandler.Failure.SESSION_EXPIRED) {
                            assertThat(request.getSession(false)).isNull();
                        } else {
                            assertThat(request.getSession(false)).isNotNull();
                        }
                        response.setStatus(error == LasSsoClientErrorHandler.Failure.SESSION_EXPIRED ? 401 : 503);
                        response.setHeader("X-Application-Error", error.name());
                    }).run(context -> {
                assertThat(context).hasNotFailed();
                var login = login(context, op, "sid-refresh");
                OAuth2AuthorizedClient client = load(context, login);
                Instant now = Instant.now();
                // Set only the Token freshness fixture; the authentication itself came from signed Code login.
                context.getBean(LasSsoAuthorizedClientRepository.class).saveAuthorizedClient(
                        new OAuth2AuthorizedClient(client.getClientRegistration(), client.getPrincipalName(),
                                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                                        client.getAccessToken().getTokenValue(), now.minusSeconds(900), now.minusSeconds(1)),
                                client.getRefreshToken()), authentication(login.session()),
                        request(context, login.session(), "GET", "/api/private"), new MockHttpServletResponse());
                var response = perform(context, request(context, login.session(), "GET", "/api/private"));
                var expected = failure == RefreshFailure.TERMINAL ? LasSsoClientErrorHandler.Failure.SESSION_EXPIRED
                        : LasSsoClientErrorHandler.Failure.SSO_UNAVAILABLE;
                assertThat(handled).containsExactly(expected);
                assertThat(response.getHeader("X-Application-Error")).isEqualTo(expected.name());
                assertThat(response.getStatus()).isEqualTo(failure == RefreshFailure.TERMINAL ? 401 : 503);
                assertThat(login.session().isInvalid()).isEqualTo(failure == RefreshFailure.TERMINAL);
            });
        }
    }

    private enum RefreshFailure { TERMINAL, TEMPORARY }

    @Test
    void publicConfigurerRejectsDuplicateInstallationOnTheSameChain() throws Exception {
        try (var op = new OidcEndpoint()) {
            runner(op).withUserConfiguration(DuplicateConfiguration.class).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasStackTraceContaining("only once per chain");
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateConfiguration {
        @Bean
        SecurityFilterChain duplicateChain(HttpSecurity http, LasSsoSecurityConfigurer las) throws Exception {
            var success = new SimpleUrlAuthenticationSuccessHandler("/");
            las.configure(http, success);
            las.configure(http, success);
            return http.build();
        }
    }

    @Test
    void laterSuccessfulLoginReplacesEarlierSidMapping() throws Exception {
        try (var op = new OidcEndpoint()) {
            var sessions = new RecordingSessions();
            runner(op).withBean(LasSsoBackChannelSessionStore.class, () -> sessions).run(context -> {
                assertThat(context).hasNotFailed();
                var original = login(context, op, "sid-old");
                var replacement = login(context, op, "sid-new", original.session());
                // The request fixture explicitly dispatches the ID listener; no real Servlet container is started.
                assertThat(sessions.registrations).contains(replacement.session().getId());
                assertThat(sessions.invalidate("sid-old", SUBJECT)).isZero();
                assertThat(replacement.session().isInvalid()).isFalse();
                assertThat(backChannel(context, op.logoutToken("sid-new")).getStatus()).isEqualTo(204);
                assertThat(replacement.session().isInvalid()).isTrue();
            });
        }
    }

    @Test
    void failedSessionIdReindexEndsLocalSession() throws Exception {
        try (var op = new OidcEndpoint()) {
            var sessions = new RecordingSessions();
            runner(op).withBean(LasSsoBackChannelSessionStore.class, () -> sessions).run(context -> {
                assertThat(context).hasNotFailed();
                var login = login(context, op, "sid-reindex-failed");
                var listener = (LasSsoSessionLifecycleListener) context
                        .getBean("lasSsoSessionLifecycleListener", ServletListenerRegistrationBean.class).getListener();
                String old = login.session().getId();
                login.session().changeSessionId();
                sessions.fail = true;
                assertThatThrownBy(() -> listener.sessionIdChanged(new HttpSessionEvent(login.session()), old))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(login.session().isInvalid()).isTrue();
                assertThat(sessions.unregistered).contains(old);
            });
        }
    }

    private static WebApplicationContextRunner runner(OidcEndpoint op) {
        var registration = ClientRegistration.withRegistrationId("las-sso")
                .clientId("callback-client").clientSecret("callback-test-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(CALLBACK).scope("openid")
                .authorizationUri(op.issuer() + "/oauth2/authorize")
                .tokenUri(op.issuer() + "/oauth2/token")
                .jwkSetUri(op.issuer() + "/oauth2/jwks")
                .issuerUri(op.issuer()).userNameAttributeName("sub").build();
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LasSsoClientAutoConfiguration.class))
                .withUserConfiguration(SecurityConfiguration.class)
                .withBean(ClientRegistrationRepository.class,
                        () -> new InMemoryClientRegistrationRepository(registration));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class SecurityConfiguration {
    }

    private static Login login(WebApplicationContext context, OidcEndpoint op, String sid) throws Exception {
        return login(context, op, sid, null);
    }

    private static ReturnTransaction beginReturn(WebApplicationContext context, String target, MockHttpSession existing)
            throws Exception {
        var entry = request(context, existing, "GET", "/api/auth/login");
        entry.addParameter("returnTo", target);
        var entryResponse = perform(context, entry);
        assertThat(entryResponse.getStatus()).isEqualTo(302);
        var authorization = request(context, existing, "GET", "/oauth2/authorization/las-sso");
        form(URI.create(entryResponse.getRedirectedUrl()).getRawQuery()).forEach(authorization::addParameter);
        var redirect = perform(context, authorization);
        assertThat(redirect.getStatus()).isEqualTo(302);
        var parameters = form(URI.create(redirect.getRedirectedUrl()).getRawQuery());
        assertThat(parameters).doesNotContainKeys("returnTo", "return_to");
        assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
        return new ReturnTransaction((MockHttpSession) authorization.getSession(false), parameters);
    }

    private static MockHttpServletRequest returnCallback(WebApplicationContext context, OidcEndpoint op,
            ReturnTransaction transaction, String sid) {
        var callback = request(context, transaction.session(), "GET", "/login/oauth2/code/las-sso");
        callback.addParameter("code", op.issueCode(transaction.parameters(), sid));
        callback.addParameter("state", transaction.parameters().get("state"));
        return callback;
    }

    private record ReturnTransaction(MockHttpSession session, Map<String, String> parameters) { }

    private static Login login(WebApplicationContext context, OidcEndpoint op, String sid,
            MockHttpSession existing) throws Exception {
        var begin = request(context, existing, "GET", "/oauth2/authorization/las-sso");
        var redirect = perform(context, begin);
        assertThat(redirect.getStatus()).isEqualTo(302);
        var parameters = form(URI.create(redirect.getRedirectedUrl()).getRawQuery());
        assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
        assertThat(parameters.get("nonce")).isNotBlank();
        var session = (MockHttpSession) begin.getSession(false);
        String previousId = session.getId();
        String code = op.issueCode(parameters, sid);
        var callback = request(context, session, "GET", "/login/oauth2/code/las-sso");
        callback.addParameter("code", code);
        callback.addParameter("state", parameters.get("state"));
        return new Login(session, previousId, perform(context, callback));
    }

    private static Authentication authentication(MockHttpSession session) {
        return ((SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).getAuthentication();
    }

    private static OAuth2AuthorizedClient load(WebApplicationContext context, Login login) {
        return context.getBean(LasSsoAuthorizedClientRepository.class).loadAuthorizedClient(
                "las-sso", authentication(login.session()),
                request(context, login.session(), "GET", "/api/private"));
    }

    private static MockHttpServletRequest request(WebApplicationContext context,
            MockHttpSession session, String method, String path) {
        var request = new MockHttpServletRequest(context.getServletContext(), method, path) {
            @Override
            public String changeSessionId() {
                String old = getSession(false).getId();
                String next = super.changeSessionId();
                var listener = (LasSsoSessionLifecycleListener) context
                        .getBean("lasSsoSessionLifecycleListener", ServletListenerRegistrationBean.class).getListener();
                listener.sessionIdChanged(new HttpSessionEvent(getSession(false)), old);
                return next;
            }
        };
        request.setServletPath(path);
        if (session != null) request.setSession(session);
        return request;
    }

    private static MockHttpServletResponse backChannel(WebApplicationContext context, String token)
            throws Exception {
        var request = request(context, null, "POST", "/internal/oidc/backchannel-logout");
        request.setContentType("application/x-www-form-urlencoded");
        request.addParameter("logout_token", token);
        return perform(context, request);
    }

    private static MockHttpServletResponse perform(WebApplicationContext context, MockHttpServletRequest request)
            throws Exception {
        var response = new MockHttpServletResponse();
        // Avoid mocking the controller or decoder. Servlet dispatch/binding itself is outside this test.
        var terminal = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res)
                    throws ServletException, IOException {
                if ("/internal/oidc/backchannel-logout".equals(req.getRequestURI())) {
                    var result = context.getBean(LasSsoBackChannelLogoutController.class)
                            .logout(req.getParameter("logout_token"));
                    res.setStatus(result.getStatusCode().value());
                    result.getHeaders().forEach((name, values) -> values.forEach(value -> res.addHeader(name, value)));
                } else if ("/api/auth/login".equals(req.getRequestURI())) {
                    var result = context.getBean(LasSsoSessionController.class).login(req);
                    res.setStatus(result.getStatusCode().value());
                    result.getHeaders().forEach((name, values) -> values.forEach(value -> res.addHeader(name, value)));
                }
            }
        };
        context.getBean(FilterChainProxy.class).doFilter(request, response, new MockFilterChain(terminal));
        return response;
    }

    private static Map<String, String> form(String form) {
        return Arrays.stream(form.split("&")).map(value -> value.split("=", 2))
                .collect(Collectors.toMap(value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8),
                        value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8)));
    }

    private record Login(MockHttpSession session, String preLoginSessionId, MockHttpServletResponse response) {
    }

    private static final class RecordingSessions implements LasSsoBackChannelSessionStore {
        final LasSsoBackChannelSessionRegistry delegate = new LasSsoBackChannelSessionRegistry();
        final List<String> registrations = new CopyOnWriteArrayList<>();
        final List<String> unregistered = new CopyOnWriteArrayList<>();
        boolean fail;

        @Override
        public void register(String sid, String subject, HttpSession session) {
            if (fail) throw new IllegalStateException("Fixture index unavailable");
            registrations.add(session.getId());
            delegate.register(sid, subject, session);
        }

        @Override
        public int invalidate(String sid, String subject) { return delegate.invalidate(sid, subject); }

        @Override
        public void unregister(String sessionId) {
            unregistered.add(sessionId);
            delegate.unregister(sessionId);
        }
    }

    private static final class RecordingTokens implements LasSsoAuthorizedClientRepository {
        final DefaultLasSsoAuthorizedClientRepository delegate = new DefaultLasSsoAuthorizedClientRepository();
        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger removals = new AtomicInteger();

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String id, Authentication principal,
                HttpServletRequest request) {
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

    private static final class OidcEndpoint implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final RSAKey key = new RSAKeyGenerator(2048).keyID("callback-test-key").generate();
        final RSAKey wrongKey = new RSAKeyGenerator(2048).keyID("callback-test-key").generate();
        final Map<String, Code> codes = new ConcurrentHashMap<>();
        final AtomicInteger exchanges = new AtomicInteger();
        final AtomicInteger jwksRequests = new AtomicInteger();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        InvalidToken invalid;
        RefreshFailure refreshFailure;
        volatile Runnable beforeTokenResponse = () -> { };

        OidcEndpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/oauth2/jwks", exchange -> {
                jwksRequests.incrementAndGet();
                json(exchange, 200, new JWKSet(key.toPublicJWK()).toString());
            });
            server.createContext("/oauth2/token", this::token);
            server.setExecutor(executor);
            server.start();
        }

        String issuer() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String issueCode(Map<String, String> request, String sid) {
            String code = UUID.randomUUID().toString();
            codes.put(code, new Code(request.get("nonce"), request.get("code_challenge"), sid));
            return code;
        }

        String logoutToken(String sid) throws Exception {
            var now = Instant.now();
            return sign(new JWTClaimsSet.Builder().issuer(issuer()).audience("callback-client")
                    .subject(SUBJECT).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(120)))
                    .jwtID(UUID.randomUUID().toString()).claim("sid", sid)
                    .claim("events", Map.of("http://schemas.openid.net/event/backchannel-logout", Map.of()))
                    .build(), "logout+jwt");
        }

        private String sign(JWTClaimsSet claims, String type) throws Exception {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(new JOSEObjectType(type)).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner("JWT".equals(type) && invalid == InvalidToken.SIGNATURE ? wrongKey : key));
            return jwt.serialize();
        }

        private void token(HttpExchange exchange) throws IOException {
            try {
                var parameters = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String expected = "Basic " + Base64.getEncoder().encodeToString(
                        "callback-client:callback-test-secret".getBytes(StandardCharsets.UTF_8));
                if ("refresh_token".equals(parameters.get("grant_type"))) {
                    boolean valid = expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                            && "refresh-sid-refresh".equals(parameters.get("refresh_token"));
                    if (!valid) {
                        json(exchange, 400, "{\"error\":\"invalid_request\"}");
                    } else if (refreshFailure == RefreshFailure.TERMINAL) {
                        json(exchange, 400, "{\"error\":\"invalid_grant\"}");
                    } else {
                        json(exchange, 503, "{\"error\":\"temporarily_unavailable\"}");
                    }
                    return;
                }
                var code = codes.remove(parameters.get("code"));
                String verifier = parameters.get("code_verifier");
                String challenge = verifier == null ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                if (!"POST".equals(exchange.getRequestMethod())
                        || !expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        || !"authorization_code".equals(parameters.get("grant_type"))
                        || !CALLBACK.equals(parameters.get("redirect_uri"))
                        || code == null || !challenge.equals(code.challenge())) {
                    json(exchange, 400, "{\"error\":\"invalid_grant\"}");
                    return;
                }
                var now = Instant.now();
                String idToken = sign(new JWTClaimsSet.Builder().issuer(issuer()).audience("callback-client")
                        .subject(SUBJECT).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600)))
                        .claim("nonce", invalid == InvalidToken.NONCE ? "mismatched-nonce" : code.nonce())
                        .claim("sid", invalid == InvalidToken.MISSING_SID ? null : code.sid())
                        .claim("auth_time", now.getEpochSecond()).build(), "JWT");
                exchanges.incrementAndGet();
                beforeTokenResponse.run();
                json(exchange, 200, "{\"access_token\":\"access-" + code.sid()
                        + "\",\"refresh_token\":\"refresh-" + code.sid()
                        + "\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"openid\",\"id_token\":\""
                        + idToken + "\"}");
            } catch (Exception exception) {
                failures.add(exception);
                json(exchange, 500, "{\"error\":\"server_error\"}");
            }
        }

        private static void json(HttpExchange exchange, int status, String body) throws IOException {
            try (exchange) {
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
            assertThat(failures).isEmpty();
        }

        private record Code(String nonce, String challenge, String sid) {
        }

        private enum InvalidToken { SIGNATURE, NONCE, MISSING_SID }
    }
}
