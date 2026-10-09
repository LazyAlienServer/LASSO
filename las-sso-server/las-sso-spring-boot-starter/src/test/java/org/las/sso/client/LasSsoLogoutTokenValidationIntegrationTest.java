package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpSession;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Actual auto-configured Nimbus decoder/RSA/JWKS and receiver; Servlet dispatch is outside this
 * seam.
 */
@ExtendWith(OutputCaptureExtension.class)
class LasSsoLogoutTokenValidationIntegrationTest {

    private static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";

    @ParameterizedTest
    @EnumSource(InvalidClaim.class)
    void signedButInvalidLogoutClaimsAreRejectedBeforeAnySessionExecution(
            InvalidClaim invalid, CapturedOutput output) throws Exception {
        try (var op = new SigningEndpoint()) {
            var calls = new AtomicInteger();
            runner(op, calls)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                String token = op.token(invalid);
                                if (invalid == InvalidClaim.SUBJECT_NUMBER) {
                                    assertThat(
                                                    SignedJWT.parse(token)
                                                            .getPayload()
                                                            .toJSONObject()
                                                            .get("sub"))
                                            .isInstanceOf(Number.class);
                                }
                                var result =
                                        context.getBean(LasSsoBackChannelLogoutController.class)
                                                .logout(token);

                                assertThat(result.getStatusCode().value()).isEqualTo(400);
                                assertThat(result.getHeaders().getCacheControl())
                                        .isEqualTo("no-store");
                                assertThat(calls).hasValue(0);
                                assertThat(output.getAll())
                                        .doesNotContain(token, "secret-claim-canary");
                            });
        }
    }

    @Test
    void identicalSignedTokenReturnsCachedSuccessWithoutExecutingTwice() throws Exception {
        try (var op = new SigningEndpoint()) {
            var calls = new AtomicInteger();
            runner(op, calls)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                String token = op.token(null);
                                var receiver =
                                        context.getBean(LasSsoBackChannelLogoutController.class);

                                assertThat(receiver.logout(token).getStatusCode().value())
                                        .isEqualTo(204);
                                assertThat(receiver.logout(token).getStatusCode().value())
                                        .isEqualTo(204);
                                assertThat(calls).hasValue(1);
                            });
        }
    }

    @Test
    void optionalSubjectMayBeAbsentWhenVerifiedSidIsPresent() throws Exception {
        try (var op = new SigningEndpoint()) {
            var calls = new AtomicInteger();
            runner(op, calls)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(
                                                context.getBean(
                                                                LasSsoBackChannelLogoutController
                                                                        .class)
                                                        .logout(op.token(null, false))
                                                        .getStatusCode()
                                                        .value())
                                        .isEqualTo(204);
                                assertThat(calls).hasValue(1);
                            });
        }
    }

    @Test
    void unavailableActualJwksEndpointProducesRetryable503WithoutExecutingSessions()
            throws Exception {
        try (var op = new SigningEndpoint()) {
            op.unavailable = true;
            var calls = new AtomicInteger();
            runner(op, calls)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                var result =
                                        context.getBean(LasSsoBackChannelLogoutController.class)
                                                .logout(op.token(null));
                                assertThat(result.getStatusCode().value()).isEqualTo(503);
                                assertThat(result.getHeaders().getCacheControl())
                                        .isEqualTo("no-store");
                                assertThat(calls).hasValue(0);
                            });
        }
    }

    private static ApplicationContextRunner runner(SigningEndpoint op, AtomicInteger calls) {
        var registration =
                ClientRegistration.withRegistrationId("las-sso")
                        .clientId("b4-client")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://client.example/callback")
                        .authorizationUri(op.issuer() + "/authorize")
                        .tokenUri(op.issuer() + "/token")
                        .issuerUri(op.issuer())
                        .jwkSetUri(op.issuer() + "/jwks")
                        .build();
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LasSsoClientAutoConfiguration.class))
                .withUserConfiguration(NoDefaultChain.class)
                .withBean(ClientRegistrationRepository.class, () -> id -> registration)
                .withBean(OAuth2AuthorizedClientManager.class, () -> request -> null)
                .withBean(LasSsoRevocationTaskSink.class, () -> (client, token, hint) -> {})
                .withBean(
                        LasSsoBackChannelSessionStore.class,
                        () ->
                                new LasSsoBackChannelSessionStore() {
                                    @Override
                                    public void register(
                                            String sid, String subject, HttpSession session) {}

                                    @Override
                                    public void unregister(String id) {}

                                    @Override
                                    public int invalidate(String sid, String subject) {
                                        return calls.incrementAndGet();
                                    }
                                });
    }

    @Configuration(proxyBeanMethods = false)
    static class NoDefaultChain {
        @Bean
        SecurityFilterChain securityFilterChain() {
            return org.mockito.Mockito.mock(SecurityFilterChain.class);
        }
    }

    private enum InvalidClaim {
        MISSING_IAT,
        LIFETIME_TOO_LONG,
        FUTURE_IAT,
        STALE_IAT,
        MISSING_SID,
        BLANK_SID,
        BLANK_JTI,
        EVENT_STRING,
        EVENT_NONEMPTY,
        EVENTS_ARRAY,
        MISSING_EXP,
        EXPIRED,
        MISSING_JTI,
        MISSING_EVENTS,
        EVENT_NULL,
        SID_ARRAY,
        SUBJECT_NUMBER,
        HAS_NONCE,
        WRONG_ISSUER,
        WRONG_AUDIENCE,
        WRONG_TYPE,
        WRONG_SIGNATURE,
        EXP_BEFORE_IAT,
        IAT_STRING,
        EXP_STRING,
        JTI_NUMBER,
        SID_ONLY_WRONG_TYPE,
        WRONG_ALGORITHM,
        UNKNOWN_KID,
        FUTURE_NBF
    }

    private static final class SigningEndpoint implements AutoCloseable {
        final RSAKey key = new RSAKeyGenerator(2048).keyID("b4-signing-test-key").generate();
        final RSAKey wrongKey = new RSAKeyGenerator(2048).keyID("b4-signing-test-key").generate();
        final HttpServer server;
        volatile boolean unavailable;

        SigningEndpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/jwks",
                    exchange -> {
                        if (unavailable) {
                            exchange.sendResponseHeaders(503, -1);
                            exchange.close();
                            return;
                        }
                        byte[] body =
                                new JWKSet(key.toPublicJWK())
                                        .toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.start();
        }

        String issuer() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String token(InvalidClaim invalid) throws Exception {
            return token(invalid, true);
        }

        String token(InvalidClaim invalid, boolean includeSubject) throws Exception {
            Instant now = Instant.now();
            Instant issued =
                    invalid == InvalidClaim.FUTURE_IAT
                            ? now.plusSeconds(61)
                            : invalid == InvalidClaim.STALE_IAT
                                    ? now.minusSeconds(181)
                                    : invalid == InvalidClaim.EXPIRED ? now.minusSeconds(181) : now;
            Instant expires =
                    invalid == InvalidClaim.LIFETIME_TOO_LONG
                            ? now.plusSeconds(121)
                            : invalid == InvalidClaim.FUTURE_IAT
                                    ? issued.plusSeconds(120)
                                    : invalid == InvalidClaim.EXPIRED
                                            ? now.minusSeconds(61)
                                            : invalid == InvalidClaim.EXP_BEFORE_IAT
                                                    ? now.minusSeconds(1)
                                                    : now.plusSeconds(120);
            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer(
                                    invalid == InvalidClaim.WRONG_ISSUER
                                            ? "https://other-issuer.example"
                                            : issuer())
                            .audience(
                                    invalid == InvalidClaim.WRONG_AUDIENCE
                                            ? "other-client"
                                            : "b4-client");
            if (includeSubject) claims.subject("b4-subject");
            if (invalid == InvalidClaim.SUBJECT_NUMBER) claims.claim("sub", 123);
            if (invalid != InvalidClaim.MISSING_EXP) claims.expirationTime(Date.from(expires));
            if (invalid != InvalidClaim.MISSING_JTI) {
                claims.jwtID(
                        invalid == InvalidClaim.BLANK_JTI ? " " : UUID.randomUUID().toString());
            }
            if (invalid != InvalidClaim.MISSING_IAT) claims.issueTime(Date.from(issued));
            if (invalid != InvalidClaim.MISSING_SID)
                claims.claim("sid", invalid == InvalidClaim.BLANK_SID ? " " : "b4-sid");
            if (invalid == InvalidClaim.SID_ARRAY) claims.claim("sid", List.of("b4-sid"));
            if (invalid == InvalidClaim.SID_ONLY_WRONG_TYPE) claims.claim("sid", 123);
            if (invalid == InvalidClaim.IAT_STRING) claims.claim("iat", "secret-claim-canary");
            if (invalid == InvalidClaim.EXP_STRING) claims.claim("exp", "secret-claim-canary");
            if (invalid == InvalidClaim.JTI_NUMBER) claims.claim("jti", 123);
            if (invalid == InvalidClaim.HAS_NONCE) claims.claim("nonce", "secret-claim-canary");
            if (invalid == InvalidClaim.FUTURE_NBF)
                // JWT seconds precision and real JWKS latency must not consume a one-second margin.
                claims.notBeforeTime(Date.from(now.plusSeconds(120)));
            Object event =
                    invalid == InvalidClaim.EVENT_STRING
                            ? "not-an-object"
                            : invalid == InvalidClaim.EVENT_NONEMPTY
                                    ? Map.of("unexpected", true)
                                    : Map.of();
            if (invalid != InvalidClaim.MISSING_EVENTS) {
                claims.claim(
                        "events",
                        invalid == InvalidClaim.EVENTS_ARRAY
                                ? List.of(EVENT)
                                : invalid == InvalidClaim.EVENT_NULL
                                        ? java.util.Collections.singletonMap(EVENT, null)
                                        : Map.of(EVENT, event));
            }
            var jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(
                                            invalid == InvalidClaim.WRONG_ALGORITHM
                                                    ? JWSAlgorithm.RS512
                                                    : JWSAlgorithm.RS256)
                                    .keyID(
                                            invalid == InvalidClaim.UNKNOWN_KID
                                                    ? "unpublished-key"
                                                    : key.getKeyID())
                                    .type(
                                            new JOSEObjectType(
                                                    invalid == InvalidClaim.WRONG_TYPE
                                                            ? "JWT"
                                                            : "logout+jwt"))
                                    .build(),
                            claims.build());
            jwt.sign(new RSASSASigner(invalid == InvalidClaim.WRONG_SIGNATURE ? wrongKey : key));
            return jwt.serialize();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
