package org.las.sso.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.altcha.altcha.v2.Altcha;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.las.sso.account.AccountRegistrationService;
import org.las.sso.config.LoginRiskProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Real loopback TCP/Tomcat/security/JDBC Session. Secure cookies are explicitly transported by this
 * HTTP fixture.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "server.address=127.0.0.1", "server.forward-headers-strategy=none",
            "las.sso.scheduling.enabled=false", "spring.session.jdbc.cleanup-cron=-",
            "las.sso.login-risk.captcha-window=PT240H", "las.sso.login-risk.block-window=PT240H"
        })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(30)
class ServletPathSecurityIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired Environment environment;
    @Autowired AccountRegistrationService accounts;
    @Autowired JdbcClient jdbc;
    @Autowired PostgresRateLimiter rates;
    @Autowired ClientNetworkKey networks;
    @Autowired LoginRiskProperties risk;
    @Autowired ObjectMapper json;
    @Autowired RegisteredClientRepository clients;
    @Autowired PasswordEncoder encoder;
    @Autowired AltchaChallengeService captcha;

    private static final String NETWORK = "127.0.0.1";

    @BeforeEach
    void resetLoopbackBudgetsInDisposableDatabase() {
        assertThat(environment.getRequiredProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getRequiredProperty("server.forward-headers-strategy"))
                .isEqualTo("none");
        assertThat(risk.captchaWindow()).isEqualTo(Duration.ofHours(240));
        assertThat(risk.blockWindow()).isEqualTo(Duration.ofHours(240));
        jdbc.sql(
                        "DELETE FROM rate_limit_buckets WHERE scope IN ('LOGIN_FAILURE_IP_BLOCK', 'LOGIN_FAILURE_IP_CAPTCHA', "
                                + "'FIRST_PARTY_AUTH_NETWORK', 'FIRST_PARTY_AUTH_CLIENT', 'CAPTCHA_ISSUE_IP')")
                .update();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/log%69n", "/%6cogin"})
    void blockedNetworkCannotAuthenticateThroughPlainOrEncodedLogin(String path) throws Exception {
        var account = register();
        var browser = browser();
        var csrf = browser.csrf();
        for (int count = 0; count < risk.blockIpFailures(); count++) {
            rates.consume(
                    "LOGIN_FAILURE_IP_BLOCK",
                    networks.fromRemoteAddress("127.0.0.1"),
                    risk.blockIpFailures(),
                    risk.blockWindow());
        }

        var response =
                browser.form(
                        path,
                        Map.of(
                                "username",
                                account.username(),
                                "password",
                                password(),
                                csrf.parameter(),
                                csrf.token()));

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(activeSessions(account.userId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/log%69n", "/%6cogin"})
    void ordinaryLoginStillAuthenticatesExactlyOnceAndPersistsRealJdbcSession(String path)
            throws Exception {
        var account = register();
        var browser = browser();
        var csrf = browser.csrf();

        var response =
                browser.form(
                        path,
                        Map.of(
                                "username",
                                account.username(),
                                "password",
                                password(),
                                csrf.parameter(),
                                csrf.token()));

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationPath(response)).isEqualTo(contextPath() + "/");
        assertAuthenticated(browser, account.userId());
        assertThat(activeSessions(account.userId())).isOne();
        assertThat(
                        jdbc.sql("SELECT COUNT(*) FROM spring_session WHERE principal_name = :id")
                                .param("id", account.userId().toString())
                                .query(Long.class)
                                .single())
                .isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/log%69n", "/%6cogin"})
    void missingCsrfCannotAuthenticateOrAdvanceLoginFailureBudgets(String path) throws Exception {
        var account = register();
        var browser = browser();
        browser.csrf();

        var response =
                browser.form(path, Map.of("username", account.username(), "password", password()));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(activeSessions(account.userId())).isZero();
        assertThat(loginFailures()).isZero();
        assertThat(failedAttempts(account.userId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/log%69n", "/%6cogin"})
    void failedCredentialsOnEachLoginPathAdvanceTheSameIpAndUserCounters(String path)
            throws Exception {
        var account = register();
        var browser = browser();
        var csrf = browser.csrf();

        var response =
                browser.form(
                        path,
                        Map.of(
                                "username",
                                account.username(),
                                "password",
                                "incorrect fixture password",
                                csrf.parameter(),
                                csrf.token()));

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationPath(response)).isEqualTo(contextPath() + "/login");
        assertThat(URI.create(response.headers().firstValue("Location").orElseThrow()).getQuery())
                .isEqualTo("error");
        assertThat(failedAttempts(account.userId())).isOne();
        assertThat(
                        rates.current(
                                        "LOGIN_FAILURE_IP_BLOCK",
                                        network(),
                                        risk.blockIpFailures(),
                                        risk.blockWindow())
                                .count())
                .isOne();
        assertThat(
                        rates.current(
                                        "LOGIN_FAILURE_IP_CAPTCHA",
                                        network(),
                                        risk.captchaIpFailures(),
                                        risk.captchaWindow())
                                .count())
                .isOne();
        assertThat(activeSessions(account.userId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/log%69n", "/%6cogin"})
    void missingAndMalformedCaptchaCannotReachPasswordAuthentication(String path) throws Exception {
        var account = register();
        requireCaptcha();
        for (String payload : new String[] {"", "malformed-fixture"}) {
            var browser = browser();
            var csrf = browser.csrf();
            var response =
                    browser.form(
                            path,
                            Map.of(
                                    "username",
                                    account.username(),
                                    "password",
                                    password(),
                                    csrf.parameter(),
                                    csrf.token(),
                                    "captchaPayload",
                                    payload));
            assertCaptchaRedirect(response);
        }
        assertThat(activeSessions(account.userId())).isZero();
        assertThat(failedAttempts(account.userId())).isZero();
        assertThat(loginFailures()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/log%69n", "/%6cogin"})
    void realHttpIssuedSolvedCaptchaAllowsOneLoginButCannotBeReplayed(String path)
            throws Exception {
        var account = register();
        requireCaptcha();
        var browser = browser();
        var csrf = browser.csrf();
        var issued = browser.issueCaptcha(csrf, AltchaChallengeService.Purpose.LOGIN_RISK);
        String proof = solve(issued);

        var response =
                browser.form(
                        path,
                        Map.of(
                                "username",
                                account.username(),
                                "password",
                                password(),
                                csrf.parameter(),
                                csrf.token(),
                                "captchaPayload",
                                proof));

        assertThat(response.statusCode()).isEqualTo(302);
        assertAuthenticated(browser, account.userId());
        assertThat(activeSessions(account.userId())).isOne();
        assertThat(challengeConsumed(issued.challengeId())).isTrue();
        var replay = browser();
        var replayCsrf = replay.csrf();
        assertCaptchaRedirect(
                replay.form(
                        path,
                        Map.of(
                                "username",
                                account.username(),
                                "password",
                                password(),
                                replayCsrf.parameter(),
                                replayCsrf.token(),
                                "captchaPayload",
                                proof)));
        assertThat(activeSessions(account.userId())).isOne();
    }

    @Test
    void invalidPasswordConsumesTheValidProofOnceAndStillAdvancesFailureCounters()
            throws Exception {
        var account = register();
        requireCaptcha();
        var browser = browser();
        var csrf = browser.csrf();
        var issued = browser.issueCaptcha(csrf, AltchaChallengeService.Purpose.LOGIN_RISK);
        String proof = solve(issued);

        var rejected =
                browser.form(
                        "/log%69n",
                        Map.of(
                                "username",
                                account.username(),
                                "password",
                                "incorrect fixture password",
                                csrf.parameter(),
                                csrf.token(),
                                "captchaPayload",
                                proof));

        assertThat(rejected.statusCode()).isEqualTo(302);
        assertThat(URI.create(rejected.headers().firstValue("Location").orElseThrow()).getQuery())
                .isEqualTo("error");
        assertThat(challengeConsumed(issued.challengeId())).isTrue();
        assertThat(loginFailures()).isOne();
        assertThat(failedAttempts(account.userId())).isOne();
        assertCaptchaRedirect(
                browser.form(
                        "/login",
                        Map.of(
                                "username",
                                account.username(),
                                "password",
                                password(),
                                csrf.parameter(),
                                csrf.token(),
                                "captchaPayload",
                                proof)));
        assertThat(activeSessions(account.userId())).isZero();
        assertThat(loginFailures()).isOne();
    }

    @Test
    void csrfRejectionDoesNotConsumeAnOtherwiseValidCaptcha() throws Exception {
        var account = register();
        requireCaptcha();
        var browser = browser();
        var csrf = browser.csrf();
        var issued = browser.issueCaptcha(csrf, AltchaChallengeService.Purpose.LOGIN_RISK);
        String proof = solve(issued);

        var response =
                browser.form(
                        "/log%69n",
                        Map.of(
                                "username",
                                account.username(),
                                "password",
                                password(),
                                "captchaPayload",
                                proof));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(challengeConsumed(issued.challengeId())).isFalse();
        assertThat(loginFailures()).isZero();
        assertThat(activeSessions(account.userId())).isZero();
        assertThat(
                        browser.form(
                                        "/login",
                                        Map.of(
                                                "username",
                                                account.username(),
                                                "password",
                                                password(),
                                                csrf.parameter(),
                                                csrf.token(),
                                                "captchaPayload",
                                                proof))
                                .statusCode())
                .isEqualTo(302);
        assertAuthenticated(browser, account.userId());
    }

    @Test
    void networkBlockRejectsBeforeConsumingProofAndIgnoresUntrustedForwardedAddress()
            throws Exception {
        var account = register();
        var browser = browser();
        var csrf = browser.csrf();
        var issued = browser.issueCaptcha(csrf, AltchaChallengeService.Purpose.LOGIN_RISK);
        String proof = solve(issued);
        for (int count = 0; count < risk.blockIpFailures(); count++) {
            rates.consume(
                    "LOGIN_FAILURE_IP_BLOCK",
                    network(),
                    risk.blockIpFailures(),
                    risk.blockWindow());
        }
        String body =
                "username="
                        + account.username()
                        + "&password="
                        + URLEncoder.encode(password(), StandardCharsets.UTF_8)
                        + "&"
                        + csrf.parameter()
                        + "="
                        + URLEncoder.encode(csrf.token(), StandardCharsets.UTF_8)
                        + "&captchaPayload="
                        + URLEncoder.encode(proof, StandardCharsets.UTF_8);

        var response =
                browser.send(
                        "POST",
                        "/log%69n",
                        body,
                        Map.of(
                                "Content-Type",
                                "application/x-www-form-urlencoded",
                                "X-Forwarded-For",
                                "192.0.2.10",
                                "Forwarded",
                                "for=192.0.2.10"));

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(challengeConsumed(issued.challengeId())).isFalse();
        assertThat(failedAttempts(account.userId())).isZero();
        assertThat(activeSessions(account.userId())).isZero();
    }

    @Test
    void wrongPurposeWrongNetworkAndExpiredProofsStayRejectedOnEncodedLogin() throws Exception {
        var account = register();
        requireCaptcha();
        for (String invalid : new String[] {"purpose", "network", "expiry"}) {
            // Explicit scope/network/time fixtures; the valid-proof test above uses real HTTP
            // issuance.
            var issued =
                    captcha.issue(
                            invalid.equals("purpose")
                                    ? AltchaChallengeService.Purpose.REGISTER
                                    : AltchaChallengeService.Purpose.LOGIN_RISK,
                            invalid.equals("network") ? "v4/24:c0000200" : network());
            String proof = solve(issued);
            if (invalid.equals("expiry")) {
                jdbc.sql(
                                "UPDATE captcha_challenges SET created_at = CURRENT_TIMESTAMP - INTERVAL '6 minutes', "
                                        + "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = :id")
                        .param("id", issued.challengeId())
                        .update();
            }
            var browser = browser();
            var csrf = browser.csrf();
            assertCaptchaRedirect(
                    browser.form(
                            "/log%69n",
                            Map.of(
                                    "username",
                                    account.username(),
                                    "password",
                                    password(),
                                    csrf.parameter(),
                                    csrf.token(),
                                    "captchaPayload",
                                    proof)));
            assertThat(challengeConsumed(issued.challengeId())).isFalse();
        }
        assertThat(activeSessions(account.userId())).isZero();
        assertThat(failedAttempts(account.userId())).isZero();
    }

    @Test
    void actualHttpFailuresTriggerCaptchaThenIpBlockAcrossPlainAndEncodedAttempts()
            throws Exception {
        var browser = browser();
        var csrf = browser.csrf();
        for (int attempt = 0; attempt < 2; attempt++) {
            var response =
                    browser.form(
                            attempt == 0 ? "/login" : "/log%69n",
                            Map.of(
                                    "username",
                                    "UnknownHttpFixture",
                                    "password",
                                    "incorrect fixture password",
                                    csrf.parameter(),
                                    csrf.token()));
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(
                            URI.create(response.headers().firstValue("Location").orElseThrow())
                                    .getQuery())
                    .isEqualTo("error");
        }
        assertCaptchaRedirect(
                browser.form(
                        "/%6cogin",
                        Map.of(
                                "username",
                                "UnknownHttpFixture",
                                "password",
                                "incorrect fixture password",
                                csrf.parameter(),
                                csrf.token())));
        var issued = browser.issueCaptcha(csrf, AltchaChallengeService.Purpose.LOGIN_RISK);
        browser.form(
                "/log%69n",
                Map.of(
                        "username",
                        "UnknownHttpFixture",
                        "password",
                        "incorrect fixture password",
                        csrf.parameter(),
                        csrf.token(),
                        "captchaPayload",
                        solve(issued)));
        var response =
                browser.form(
                        "/login",
                        Map.of(
                                "username",
                                "UnknownHttpFixture",
                                "password",
                                "incorrect fixture password",
                                csrf.parameter(),
                                csrf.token()));
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(
                        rates.current(
                                        "LOGIN_FAILURE_IP_BLOCK",
                                        network(),
                                        risk.blockIpFailures(),
                                        risk.blockWindow())
                                .count())
                .isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "principals", "internal", "service"})
    void validFirstPartyBasicGetsSameKnownAndUnknownDecisionsOnEncodedInternalPaths(String variant)
            throws Exception {
        var account = register();
        var client = client("FIRST_PARTY", "ACTIVE", true, true);
        for (UUID subject : new UUID[] {account.userId(), UUID.randomUUID()}) {
            var response =
                    browser()
                            .send(
                                    "GET",
                                    internalPath(subject, variant),
                                    null,
                                    Map.of("Authorization", basic(client)));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            var body = json.readTree(response.body());
            assertThat(body.get("subject").asText()).isEqualTo(subject.toString());
            assertThat(body.get("decision").asText())
                    .isEqualTo(subject.equals(account.userId()) ? "ALLOWED" : "DENIED");
            assertThat(body.get("reason").asText())
                    .isEqualTo(subject.equals(account.userId()) ? "ACTIVE" : "PRINCIPAL_NOT_FOUND");
            assertThat(body.has("username")).isFalse();
            assertThat(body.has("administrator")).isFalse();
        }
        assertThat(
                        jdbc.sql(
                                        "SELECT COUNT(*) FROM rate_limit_buckets WHERE scope LIKE 'FIRST_PARTY_AUTH_%'")
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "principals", "internal", "service"})
    void anonymousCallerCannotReadInternalKnownOrUnknownSubjectOnEncodedPaths(String variant)
            throws Exception {
        var account = register();
        for (UUID subject : new UUID[] {account.userId(), UUID.randomUUID()}) {
            assertInternalRejected(
                    browser().send("GET", internalPath(subject, variant), null, Map.of()), subject);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "principals", "internal", "service"})
    void realOrdinaryUserCookieCannotReplaceDedicatedFirstPartyBasic(String variant)
            throws Exception {
        var account = register();
        var browser = login(account);

        assertInternalRejected(
                browser.send("GET", internalPath(account.userId(), variant), null, Map.of()),
                account.userId());

        assertAuthenticated(browser, account.userId());
    }

    @Test
    void wrongUnknownPartnerDisabledAndUnregisteredClientsStayRejectedOnEncodedPath()
            throws Exception {
        var account = register();
        var firstParty = client("FIRST_PARTY", "ACTIVE", true, true);
        var headers =
                new String[] {
                    basic(new Client(firstParty.id(), "incorrect secret")),
                    basic(new Client("unknown-" + UUID.randomUUID(), "unknown fixture secret")),
                    basic(client("REVIEWED_PARTNER", "ACTIVE", true, true)),
                    basic(client("FIRST_PARTY", "DISABLED", true, true)),
                    basic(client("FIRST_PARTY", "ACTIVE", false, true)),
                    basic(client("FIRST_PARTY", "ACTIVE", true, false)),
                    "Basic not-base64",
                    "Bearer irrelevant-fixture"
                };
        for (String authorization : headers) {
            assertInternalRejected(
                    browser()
                            .send(
                                    "GET",
                                    internalPath(account.userId(), "principals"),
                                    null,
                                    Map.of("Authorization", authorization)),
                    account.userId());
        }
    }

    @Test
    void successfulServiceAuthenticationDoesNotOverwriteAnExistingHumanJdbcSession()
            throws Exception {
        var account = register();
        var browser = login(account);
        var response =
                browser.send(
                        "GET",
                        internalPath(account.userId(), "principals"),
                        null,
                        Map.of(
                                "Authorization",
                                basic(client("FIRST_PARTY", "ACTIVE", true, true))));
        assertThat(response.statusCode()).isEqualTo(200);
        assertAuthenticated(browser, account.userId());
        assertThat(activeSessions(account.userId())).isOne();
    }

    @Test
    void exhaustedClientBudgetDeniesCorrectBasicOnBothPlainAndEncodedRoutes() throws Exception {
        var account = register();
        var client = client("FIRST_PARTY", "ACTIVE", true, true);
        for (int count = 0; count < 10; count++) {
            assertThat(
                            browser()
                                    .send(
                                            "GET",
                                            internalPath(account.userId(), "plain"),
                                            null,
                                            Map.of(
                                                    "Authorization",
                                                    basic(
                                                            new Client(
                                                                    client.id(),
                                                                    "incorrect secret"))))
                                    .statusCode())
                    .isEqualTo(401);
        }
        for (String path : new String[] {"plain", "principals"}) {
            var response =
                    browser()
                            .send(
                                    "GET",
                                    internalPath(account.userId(), path),
                                    null,
                                    Map.of("Authorization", basic(client)));
            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("Retry-After")).isPresent();
            assertThat(json.readTree(response.body()).get("code").asText())
                    .isEqualTo("RATE_LIMITED");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/log%2569n", "/login%2f", "/login;fixture=x", "/login%00", "/login%5c"})
    void ambiguousLoginPathsCannotCreateAUserSession(String path) throws Exception {
        var account = register();
        var browser = browser();
        var csrf = browser.csrf();
        var response =
                browser.form(
                        path,
                        Map.of(
                                "username",
                                account.username(),
                                "password",
                                password(),
                                csrf.parameter(),
                                csrf.token()));
        assertThat(response.statusCode()).isIn(400, 403, 404);
        assertThat(activeSessions(account.userId())).isZero();
        assertThat(failedAttempts(account.userId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"double", "semicolon", "slash", "nul", "backslash"})
    void ambiguousInternalPathsCannotReturnEligibilityEvenWithCorrectBasicAndHumanCookie(
            String variant) throws Exception {
        var account = register();
        var browser = login(account);
        var client = client("FIRST_PARTY", "ACTIVE", true, true);
        String prefix = "/internal/v1/principals/" + account.userId();
        String path =
                switch (variant) {
                    case "double" ->
                            "/internal/v1/%2570rincipals/" + account.userId() + "/service-access";
                    case "semicolon" ->
                            "/internal/v1/principals;fixture=x/"
                                    + account.userId()
                                    + "/service-access";
                    case "slash" ->
                            "/internal/v1/principals%2f" + account.userId() + "/service-access";
                    case "nul" -> prefix + "/service-access%00";
                    default -> prefix + "/service-access%5c";
                };

        var response = browser.send("GET", path, null, Map.of("Authorization", basic(client)));

        assertThat(response.statusCode()).isEqualTo(400);
        // Boot's generic 400 body may echo the caller-supplied request path, not an eligibility
        // lookup.
        assertThat(response.body())
                .doesNotContain("ALLOWED", "stateVersion", "\"decision\"", "\"subject\"");
        assertAuthenticated(browser, account.userId());
    }

    protected String contextPath() {
        return "";
    }

    private AccountRegistrationService.RegistrationResult register() {
        return accounts.register(
                "Http" + UUID.randomUUID().toString().substring(0, 8), "HTTP fixture", password());
    }

    private String password() {
        return "real servlet owner password phrase";
    }

    private long activeSessions(UUID id) {
        return jdbc.sql(
                        "SELECT COUNT(*) FROM user_sessions WHERE user_id = :id AND status = 'ACTIVE'")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private int failedAttempts(UUID id) {
        return jdbc.sql("SELECT failed_attempts FROM password_credentials WHERE user_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private int loginFailures() {
        return rates.current(
                        "LOGIN_FAILURE_IP_BLOCK",
                        network(),
                        risk.blockIpFailures(),
                        risk.blockWindow())
                .count();
    }

    private String network() {
        return networks.fromRemoteAddress(NETWORK);
    }

    private void requireCaptcha() {
        for (int count = 0; count < risk.captchaIpFailures(); count++) {
            rates.consume(
                    "LOGIN_FAILURE_IP_CAPTCHA",
                    network(),
                    risk.captchaIpFailures(),
                    risk.captchaWindow());
        }
    }

    private boolean challengeConsumed(UUID id) {
        return jdbc.sql("SELECT consumed_at IS NOT NULL FROM captcha_challenges WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private String solve(AltchaChallengeService.IssuedChallenge issued) throws Exception {
        var challenge = new Altcha.Challenge(issued.parameters(), issued.signature());
        return Base64.getEncoder()
                .encodeToString(
                        json.writeValueAsBytes(
                                new Altcha.Payload(
                                        challenge,
                                        Altcha.solveChallenge(
                                                challenge, Altcha.kdf("PBKDF2/SHA-256")))));
    }

    private Browser login(AccountRegistrationService.RegistrationResult account) throws Exception {
        var browser = browser();
        var csrf = browser.csrf();
        assertThat(
                        browser.form(
                                        "/login",
                                        Map.of(
                                                "username",
                                                account.username(),
                                                "password",
                                                password(),
                                                csrf.parameter(),
                                                csrf.token()))
                                .statusCode())
                .isEqualTo(302);
        assertAuthenticated(browser, account.userId());
        return browser;
    }

    private void assertAuthenticated(Browser browser, UUID id) throws Exception {
        var response = browser.send("GET", "/api/v1/me/profile", null, Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        String username =
                jdbc.sql("SELECT username FROM usernames WHERE user_id = :id AND valid_to IS NULL")
                        .param("id", id)
                        .query(String.class)
                        .single();
        assertThat(json.readTree(response.body()).get("username").asText()).isEqualTo(username);
    }

    private void assertCaptchaRedirect(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationPath(response)).isEqualTo(contextPath() + "/login");
        assertThat(URI.create(response.headers().firstValue("Location").orElseThrow()).getQuery())
                .isEqualTo("captcha");
    }

    private String locationPath(HttpResponse<String> response) {
        return URI.create(response.headers().firstValue("Location").orElseThrow()).getPath();
    }

    private void assertInternalRejected(HttpResponse<String> response, UUID id) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .contains("Basic realm=\"LAS SSO internal\"");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain(id.toString(), "ALLOWED", "PRINCIPAL_NOT_FOUND");
    }

    private String internalPath(UUID id, String variant) {
        return switch (variant) {
            case "principals" -> "/internal/v1/%70rincipals/" + id + "/service-access";
            case "internal" -> "/%69nternal/v1/principals/" + id + "/service-access";
            case "service" -> "/internal/v1/principals/" + id + "/service-%61ccess";
            default -> "/internal/v1/principals/" + id + "/service-access";
        };
    }

    private Client client(String type, String status, boolean basic, boolean metadata) {
        String id = "http-service-" + UUID.randomUUID();
        String secret = "fixture-" + UUID.randomUUID();
        var client =
                RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId(id)
                        .clientSecret(encoder.encode(secret))
                        .clientAuthenticationMethod(
                                basic
                                        ? ClientAuthenticationMethod.CLIENT_SECRET_BASIC
                                        : ClientAuthenticationMethod.CLIENT_SECRET_POST)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://client.example/callback")
                        .scope("openid")
                        .build();
        clients.save(client);
        if (metadata) {
            jdbc.sql(
                            """
                    INSERT INTO registered_client_metadata
                        (registered_client_id, client_type, status, config_version, resource_audiences, require_pkce, updated_at)
                    VALUES (:id, CAST(:type AS registered_client_type), CAST(:status AS registered_client_status), 1,
                            ARRAY['urn:las:http']::text[], true, CURRENT_TIMESTAMP)
                    """)
                    .param("id", client.getId())
                    .param("type", type)
                    .param("status", status)
                    .update();
        }
        return new Client(id, secret);
    }

    private String basic(Client client) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString(
                                (client.id() + ":" + client.secret())
                                        .getBytes(StandardCharsets.ISO_8859_1));
    }

    private Browser browser() {
        return new Browser(
                "http://127.0.0.1:"
                        + environment.getRequiredProperty("local.server.port")
                        + contextPath());
    }

    private final class Browser {
        final String origin;
        final HttpClient http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        final Map<String, String> cookies = new LinkedHashMap<>();

        Browser(String origin) {
            this.origin = origin;
        }

        Csrf csrf() throws Exception {
            var response = send("GET", "/api/v1/csrf", null, Map.of());
            assertThat(response.statusCode()).isEqualTo(200);
            var document = json.readTree(response.body());
            assertThat(cookies).isNotEmpty();
            return new Csrf(
                    document.get("parameterName").asText(),
                    document.get("headerName").asText(),
                    document.get("token").asText());
        }

        AltchaChallengeService.IssuedChallenge issueCaptcha(
                Csrf csrf, AltchaChallengeService.Purpose purpose) throws Exception {
            // Issuer remains the fixed test origin; this header is explicit test input, not a
            // proxy-origin drill.
            String serverOrigin = environment.getRequiredProperty("las.sso.issuer");
            var response =
                    send(
                            "POST",
                            "/api/v1/captcha/challenges",
                            "{\"purpose\":\"" + purpose.name() + "\"}",
                            Map.of(
                                    "Content-Type",
                                    "application/json",
                                    "Origin",
                                    serverOrigin,
                                    csrf.header(),
                                    csrf.token()));
            assertThat(response.statusCode()).isEqualTo(201);
            return json.readValue(response.body(), AltchaChallengeService.IssuedChallenge.class);
        }

        HttpResponse<String> form(String path, Map<String, String> values) throws Exception {
            String body =
                    values.entrySet().stream()
                            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                            .collect(java.util.stream.Collectors.joining("&"));
            return send(
                    "POST",
                    path,
                    body,
                    Map.of("Content-Type", "application/x-www-form-urlencoded"));
        }

        HttpResponse<String> send(
                String method, String path, String body, Map<String, String> headers)
                throws Exception {
            var request =
                    HttpRequest.newBuilder(URI.create(origin + path))
                            .timeout(Duration.ofSeconds(10));
            request.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            request.header("Accept", "application/json");
            if (!cookies.isEmpty())
                request.header(
                        "Cookie",
                        cookies.entrySet().stream()
                                .map(entry -> entry.getKey() + "=" + entry.getValue())
                                .collect(java.util.stream.Collectors.joining("; ")));
            headers.forEach(request::header);
            var response =
                    http.send(
                            request.build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            for (String cookie : response.headers().allValues("Set-Cookie")) {
                String pair = cookie.split(";", 2)[0];
                int separator = pair.indexOf('=');
                cookies.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
            return response;
        }

        private String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    private record Csrf(String parameter, String header, String token) {}

    private record Client(String id, String secret) {}
}
