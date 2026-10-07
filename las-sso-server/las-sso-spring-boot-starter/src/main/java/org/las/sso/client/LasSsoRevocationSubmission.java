package org.las.sso.client;

/** Request-scoped local logout submission evidence, never confirmation of OP revocation. */
public enum LasSsoRevocationSubmission {
    ACCEPTED,
    FAILED,
    NO_TOKEN;

    public static final String REQUEST_ATTRIBUTE = LasSsoRevocationSubmission.class.getName();
}
