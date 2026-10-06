package org.las.sso.client;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Response-only extension point. Local authentication cleanup happens before this is invoked. */
@FunctionalInterface
public interface LasSsoClientErrorHandler {

    void handle(HttpServletRequest request, HttpServletResponse response, Failure failure) throws IOException;

    enum Failure {
        SESSION_EXPIRED,
        SSO_UNAVAILABLE,
        SESSION_REGISTRATION_FAILED
    }
}
