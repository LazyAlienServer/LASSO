package org.las.sso.security;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import org.las.sso.config.LasSsoProperties;
import org.springframework.stereotype.Component;

@Component
public class SameOriginVerifier {

    private final URI issuer;

    public SameOriginVerifier(LasSsoProperties properties) {
        this.issuer = properties.issuer();
    }

    public void verify(HttpServletRequest request) {
        String source = request.getHeader("Origin");
        if (source == null || source.isBlank()) {
            source = request.getHeader("Referer");
        }
        if (source == null || source.isBlank()) {
            throw new RequestOriginInvalidException();
        }
        try {
            URI actual = URI.create(source);
            if (!issuer.getScheme().equalsIgnoreCase(actual.getScheme())
                    || !issuer.getHost().equalsIgnoreCase(actual.getHost())
                    || effectivePort(issuer) != effectivePort(actual)) {
                throw new RequestOriginInvalidException();
            }
        } catch (IllegalArgumentException exception) {
            throw new RequestOriginInvalidException();
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
