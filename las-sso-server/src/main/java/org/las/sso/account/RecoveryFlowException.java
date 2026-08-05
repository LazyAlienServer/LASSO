package org.las.sso.account;

public final class RecoveryFlowException extends RuntimeException {

    private final String code;

    public RecoveryFlowException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
