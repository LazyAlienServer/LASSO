package org.las.sso.account;

public class AccountDeletionConflictException extends RuntimeException {

    private final String code;

    public AccountDeletionConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
