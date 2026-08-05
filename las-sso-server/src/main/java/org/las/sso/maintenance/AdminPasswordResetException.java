package org.las.sso.maintenance;

public final class AdminPasswordResetException extends RuntimeException {

    private final String code;

    public AdminPasswordResetException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
