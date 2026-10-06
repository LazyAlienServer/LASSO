package org.las.sso.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/** Application-local return target, exposed only after verified login and session registration. */
public final class LasSsoReturnTo {

    static final String AUTH_ATTRIBUTE = LasSsoReturnTo.class.getName() + ".target";
    static final String CONSUMED_ATTRIBUTE = LasSsoReturnTo.class.getName() + ".consumed";
    private static final String VERIFIED_ATTRIBUTE = LasSsoReturnTo.class.getName() + ".verified";

    private LasSsoReturnTo() { }

    /** Available to the application's success delegate; never trusts callback query parameters. */
    public static Optional<String> target(HttpServletRequest request) {
        Object value = request.getAttribute(VERIFIED_ATTRIBUTE);
        return value instanceof String target ? Optional.of(target) : Optional.empty();
    }

    static void verified(HttpServletRequest request) {
        Object value = request.getAttribute(CONSUMED_ATTRIBUTE);
        if (value instanceof String target) request.setAttribute(VERIFIED_ATTRIBUTE, validate(target));
        request.removeAttribute(CONSUMED_ATTRIBUTE);
    }

    static String parameter(HttpServletRequest request) {
        String[] values = request.getParameterValues("returnTo");
        if (values == null) return null;
        if (values.length != 1) throw new IllegalArgumentException("Invalid return target");
        return validate(values[0]);
    }

    /** Conservative local URI policy: one leading slash, no fragment, dot segments or nested path encoding. */
    static String validate(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048
                || !value.startsWith("/") || value.startsWith("//") || unsafeCharacters(value)) {
            throw new IllegalArgumentException("Invalid return target");
        }
        try {
            URI uri = new URI(value);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Invalid return target");
            }
            String path = uri.getPath();
            String query = uri.getQuery();
            if (path == null || !path.startsWith("/") || path.startsWith("//") || unsafeCharacters(path)
                    || path.indexOf('%') >= 0 || path.indexOf(';') >= 0
                    || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                    || (query != null && (unsafeCharacters(query) || query.indexOf('%') >= 0))) {
                throw new IllegalArgumentException("Invalid return target");
            }
            for (String segment : path.split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("Invalid return target");
                }
            }
            // Encoded separators in the path can acquire new structure after another proxy/router decode.
            String rawPath = uri.getRawPath().toLowerCase(java.util.Locale.ROOT);
            if (rawPath.contains("%2f") || rawPath.contains("%5c")) {
                throw new IllegalArgumentException("Invalid return target");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid return target");
        }
    }

    private static boolean unsafeCharacters(String value) {
        return value.chars().anyMatch(character -> character == '\\'
                || Character.isISOControl(character) || Character.isWhitespace(character));
    }
}
