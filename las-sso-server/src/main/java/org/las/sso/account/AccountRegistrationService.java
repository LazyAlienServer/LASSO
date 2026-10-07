package org.las.sso.account;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.las.sso.identity.UuidV7Generator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountRegistrationService {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,24}$");

    private final JdbcClient jdbcClient;
    private final UuidV7Generator uuidGenerator;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RecoveryCodeService recoveryCodes;
    private final Clock clock;

    @Autowired
    public AccountRegistrationService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            RecoveryCodeService recoveryCodes) {
        this(
                jdbcClient,
                uuidGenerator,
                passwordEncoder,
                passwordPolicy,
                recoveryCodes,
                Clock.systemUTC());
    }

    AccountRegistrationService(
            JdbcClient jdbcClient,
            UuidV7Generator uuidGenerator,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            RecoveryCodeService recoveryCodes,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.uuidGenerator = uuidGenerator;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.recoveryCodes = recoveryCodes;
        this.clock = clock;
    }

    @Transactional
    public RegistrationResult register(String username, String displayName, String password) {
        validateProfile(username, displayName);
        passwordPolicy.validate(username, password);

        UUID userId = uuidGenerator.generate();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<RecoveryCodeService.RecoveryCode> batch = recoveryCodes.generateBatch();
        try {
            jdbcClient
                    .sql(
                            """
                    INSERT INTO users (id, display_name, account_status, created_at, updated_at)
                    VALUES (:id, :displayName, 'ACTIVE', :now, :now)
                    """)
                    .param("id", userId)
                    .param("displayName", displayName)
                    .param("now", now)
                    .update();
            jdbcClient
                    .sql(
                            """
                    INSERT INTO usernames (id, user_id, username, valid_from, changed_by_user_id, change_reason)
                    VALUES (:id, :userId, :username, :now, :userId, 'REGISTER')
                    """)
                    .param("id", uuidGenerator.generate())
                    .param("userId", userId)
                    .param("username", username)
                    .param("now", now)
                    .update();
            jdbcClient
                    .sql(
                            """
                    INSERT INTO password_credentials
                        (user_id, password_hash, state, credentials_version, password_changed_at)
                    VALUES (:userId, :passwordHash, 'ACTIVE', 1, :now)
                    """)
                    .param("userId", userId)
                    .param("passwordHash", passwordEncoder.encode(password))
                    .param("now", now)
                    .update();
            for (RecoveryCodeService.RecoveryCode code : batch) {
                jdbcClient
                        .sql(
                                """
                        INSERT INTO recovery_codes
                            (id, user_id, code_hash, generation, created_at)
                        VALUES (:id, :userId, :codeHash, 1, :now)
                        """)
                        .param("id", uuidGenerator.generate())
                        .param("userId", userId)
                        .param("codeHash", code.digest())
                        .param("now", now)
                        .update();
            }
        } catch (DataIntegrityViolationException exception) {
            throw new UsernameUnavailableException();
        }
        return new RegistrationResult(
                userId,
                username,
                displayName,
                batch.stream().map(RecoveryCodeService.RecoveryCode::plaintext).toList());
    }

    private static void validateProfile(String username, String displayName) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("USERNAME_INVALID");
        }
        if (displayName == null
                || displayName.isBlank()
                || displayName.codePointCount(0, displayName.length()) > 64) {
            throw new IllegalArgumentException("DISPLAY_NAME_INVALID");
        }
    }

    public record RegistrationResult(
            UUID userId, String username, String displayName, List<String> recoveryCodes) {
        public RegistrationResult {
            recoveryCodes = List.copyOf(recoveryCodes);
        }
    }
}
