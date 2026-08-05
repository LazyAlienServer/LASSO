package org.las.sso.account;

public final class PasswordChangeException extends RuntimeException {

    private final String code;

    public PasswordChangeException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
