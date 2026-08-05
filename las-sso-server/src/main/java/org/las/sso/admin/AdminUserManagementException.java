package org.las.sso.admin;

public final class AdminUserManagementException extends RuntimeException {

    private final String code;

    public AdminUserManagementException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
