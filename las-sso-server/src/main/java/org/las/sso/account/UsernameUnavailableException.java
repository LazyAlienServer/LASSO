package org.las.sso.account;

public final class UsernameUnavailableException extends RuntimeException {

    public UsernameUnavailableException() {
        super("USERNAME_UNAVAILABLE");
    }
}
