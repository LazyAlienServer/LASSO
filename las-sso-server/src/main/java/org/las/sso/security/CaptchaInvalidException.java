package org.las.sso.security;

public final class CaptchaInvalidException extends RuntimeException {

    public CaptchaInvalidException() {
        super("CAPTCHA_INVALID");
    }
}
