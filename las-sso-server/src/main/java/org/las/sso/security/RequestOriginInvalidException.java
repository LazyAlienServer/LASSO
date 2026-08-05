package org.las.sso.security;

public final class RequestOriginInvalidException extends RuntimeException {

    public RequestOriginInvalidException() {
        super("CSRF_INVALID");
    }
}
