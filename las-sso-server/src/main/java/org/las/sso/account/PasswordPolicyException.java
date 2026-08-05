package org.las.sso.account;

public final class PasswordPolicyException extends IllegalArgumentException {

    private final String code;

    public PasswordPolicyException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
