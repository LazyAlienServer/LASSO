package org.las.sso.security;

public final class RecentAuthenticationException extends RuntimeException {

    private final String code;

    public RecentAuthenticationException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
