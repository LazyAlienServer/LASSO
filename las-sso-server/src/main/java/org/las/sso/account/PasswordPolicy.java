package org.las.sso.account;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class PasswordPolicy {

    static final int MIN_CODE_POINTS = 12;
    static final int MAX_CODE_POINTS = 128;
    static final int MAX_UTF8_BYTES = 512;

    private final Set<String> rejectedPasswords;

    public PasswordPolicy() {
        this.rejectedPasswords = loadRejectedPasswords();
    }

    public void validate(String username, String password) {
        if (password == null) {
            throw new PasswordPolicyException("PASSWORD_REQUIRED");
        }
        int codePoints = password.codePointCount(0, password.length());
        if (codePoints < MIN_CODE_POINTS) {
            throw new PasswordPolicyException("PASSWORD_TOO_SHORT");
        }
        if (codePoints > MAX_CODE_POINTS
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new PasswordPolicyException("PASSWORD_TOO_LONG");
        }
        if (password.equals(username)) {
            throw new PasswordPolicyException("PASSWORD_EQUALS_USERNAME");
        }
        if (rejectedPasswords.contains(password.toLowerCase(java.util.Locale.ROOT))) {
            throw new PasswordPolicyException("PASSWORD_COMPROMISED");
        }
    }

    private static Set<String> loadRejectedPasswords() {
        var resource = new ClassPathResource("security/common-passwords.txt");
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(java.util.Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load common password list", exception);
        }
    }
}
