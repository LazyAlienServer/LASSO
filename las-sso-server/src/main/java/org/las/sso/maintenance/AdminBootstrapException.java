package org.las.sso.maintenance;

public final class AdminBootstrapException extends RuntimeException {

    private final String code;

    public AdminBootstrapException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
