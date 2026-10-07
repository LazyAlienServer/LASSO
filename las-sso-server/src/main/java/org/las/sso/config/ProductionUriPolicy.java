package org.las.sso.config;

import java.net.URI;
import java.util.Locale;

/** Shared syntax policy for production issuer and client endpoints; no DNS or network probing. */
public final class ProductionUriPolicy {
    public static boolean isHttpsEndpoint(String raw, boolean allowQuery) {
        if (raw == null) return false;
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme())
                    && !host.isBlank()
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && (allowQuery || uri.getQuery() == null)
                    && !raw.contains("*")
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
                    && !host.equals("localhost")
                    && !host.endsWith(".localhost")
                    && !host.startsWith("127.")
                    && !host.equals("[::1]")
                    && !host.equals("[0:0:0:0:0:0:0:1]")
                    && !host.equals("0.0.0.0")
                    && !host.endsWith(".example")
                    && !host.equals("example.com")
                    && !host.endsWith(".example.com")
                    && !host.equals("example.org")
                    && !host.endsWith(".example.org")
                    && !host.equals("example.net")
                    && !host.endsWith(".example.net");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private ProductionUriPolicy() {}
}
