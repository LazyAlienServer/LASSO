package org.las.sso.admin;

public class AdminClientException extends RuntimeException {

    private final String code;

    public AdminClientException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
