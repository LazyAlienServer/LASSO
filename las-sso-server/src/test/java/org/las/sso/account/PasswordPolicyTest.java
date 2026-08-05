package org.las.sso.account;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsLongPassphraseWithoutTrimmingSpaces() {
        assertThatCode(() -> policy.validate("Alice", "  a valid long passphrase  "))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsShortCommonAndUsernamePasswords() {
        assertThatThrownBy(() -> policy.validate("Alice", "too-short"))
                .isInstanceOf(PasswordPolicyException.class)
                .extracting("code")
                .isEqualTo("PASSWORD_TOO_SHORT");
        assertThatThrownBy(() -> policy.validate("Alice", "password1234"))
                .isInstanceOf(PasswordPolicyException.class)
                .extracting("code")
                .isEqualTo("PASSWORD_COMPROMISED");
        assertThatThrownBy(() -> policy.validate("abcdefghijkl", "abcdefghijkl"))
                .isInstanceOf(PasswordPolicyException.class)
                .extracting("code")
                .isEqualTo("PASSWORD_EQUALS_USERNAME");
    }
}
