package org.las.sso.security;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.altcha.altcha.v2.Altcha;
import org.las.sso.config.TransientSecurityProperties;
import org.las.sso.identity.UuidV7Generator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AltchaChallengeService {

    private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    private static final String ALGORITHM = "PBKDF2/SHA-256";
    private static final int COST = 5_000;

    private final PostgresCaptchaChallengeStore challengeStore;
    private final PostgresRateLimiter rateLimiter;
    private final TransientSecurityKeyHasher keyHasher;
    private final UuidV7Generator uuidGenerator;
    private final String hmacSecret;
    private final Clock clock;

    @Autowired
    public AltchaChallengeService(
            PostgresCaptchaChallengeStore challengeStore,
            PostgresRateLimiter rateLimiter,
            TransientSecurityKeyHasher keyHasher,
            UuidV7Generator uuidGenerator,
            TransientSecurityProperties properties) {
        this(
                challengeStore,
                rateLimiter,
                keyHasher,
                uuidGenerator,
                properties.captchaHmacSecret(),
                Clock.systemUTC());
    }

    AltchaChallengeService(
            PostgresCaptchaChallengeStore challengeStore,
            PostgresRateLimiter rateLimiter,
            TransientSecurityKeyHasher keyHasher,
            UuidV7Generator uuidGenerator,
            String hmacSecret,
            Clock clock) {
        this.challengeStore = challengeStore;
        this.rateLimiter = rateLimiter;
        this.keyHasher = keyHasher;
        this.uuidGenerator = uuidGenerator;
        this.hmacSecret = hmacSecret;
        this.clock = clock;
    }

    @Transactional
    public IssuedChallenge issue(Purpose purpose, String networkKey) {
        var limit = rateLimiter.consume("CAPTCHA_ISSUE_IP", networkKey, 60, Duration.ofMinutes(15));
        if (!limit.allowed()) {
            throw new RateLimitExceededException(limit.resetsAt());
        }

        UUID challengeId = uuidGenerator.generate();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plus(CHALLENGE_LIFETIME);
        var options =
                new Altcha.CreateChallengeOptions()
                        .algorithm(ALGORITHM)
                        .cost(COST)
                        .hmacSignatureSecret(hmacSecret)
                        .expiresInSeconds(CHALLENGE_LIFETIME.toSeconds())
                        .data(
                                Map.of(
                                        "challengeId", challengeId.toString(),
                                        "purpose", purpose.externalName()));
        Altcha.Challenge challenge;
        try {
            challenge = Altcha.createChallenge(options);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create CAPTCHA challenge", exception);
        }
        challengeStore.create(
                challengeId,
                purpose.databaseName(),
                keyHasher.hash("captcha-signature", challenge.signature()),
                keyHasher.hash("captcha-ip", networkKey),
                now,
                expiresAt);
        return new IssuedChallenge(
                challengeId, challenge.parameters(), challenge.signature(), expiresAt);
    }

    public void verifyAndConsume(
            UUID challengeId, Purpose purpose, String payload, String networkKey) {
        try {
            Altcha.VerifySolutionResult result =
                    Altcha.verifySolution(payload, hmacSecret, Altcha.kdf(ALGORITHM));
            if (!result.verified()) {
                throw new CaptchaInvalidException();
            }
            Altcha.Payload parsed = Altcha.parsePayload(payload);
            Map<String, Object> data = parsed.challenge().parameters().data();
            if (!challengeId.toString().equals(data.get("challengeId"))
                    || !purpose.externalName().equals(data.get("purpose"))) {
                throw new CaptchaInvalidException();
            }
            byte[] digest = keyHasher.hash("captcha-signature", parsed.challenge().signature());
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (!challengeStore.consume(
                    challengeId,
                    purpose.databaseName(),
                    digest,
                    keyHasher.hash("captcha-ip", networkKey),
                    now)) {
                throw new CaptchaInvalidException();
            }
        } catch (CaptchaInvalidException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CaptchaInvalidException();
        }
    }

    public void verifyAndConsume(Purpose purpose, String payload, String networkKey) {
        try {
            Altcha.Payload parsed = Altcha.parsePayload(payload);
            Object challengeId = parsed.challenge().parameters().data().get("challengeId");
            verifyAndConsume(
                    UUID.fromString(String.valueOf(challengeId)), purpose, payload, networkKey);
        } catch (CaptchaInvalidException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CaptchaInvalidException();
        }
    }

    public enum Purpose {
        REGISTER("REGISTER", "REGISTER"),
        LOGIN_RISK("LOGIN_RISK", "LOGIN_RISK"),
        RECOVERY_RISK("RECOVERY_RISK", "RECOVERY");

        private final String externalName;
        private final String databaseName;

        Purpose(String externalName, String databaseName) {
            this.externalName = externalName;
            this.databaseName = databaseName;
        }

        public String externalName() {
            return externalName;
        }

        public String databaseName() {
            return databaseName;
        }
    }

    public record IssuedChallenge(
            UUID challengeId,
            Altcha.ChallengeParameters parameters,
            String signature,
            OffsetDateTime expiresAt) {}
}
