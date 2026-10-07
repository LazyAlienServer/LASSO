package org.las.sso.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ErrorAuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public final class ClientAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ClientAuthenticationFailureHandler.class);
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int CLIENT_LIMIT = 10;
    private static final int IP_LIMIT = 20;

    private final PostgresRateLimiter rateLimiter;
    private final ClientNetworkKey clientNetworkKey;
    private final AuthenticationFailureHandler delegate =
            new OAuth2ErrorAuthenticationFailureHandler();

    public ClientAuthenticationFailureHandler(
            PostgresRateLimiter rateLimiter, ClientNetworkKey clientNetworkKey) {
        this.rateLimiter = rateLimiter;
        this.clientNetworkKey = clientNetworkKey;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException, ServletException {
        if (exception instanceof ClientAuthenticationRateLimitException rateLimited) {
            writeRateLimited(response, rateLimited.resetsAt());
            return;
        }
        String clientId = clientId(request);
        var clientDecision =
                rateLimiter.consume("CLIENT_SECRET_FAILURE_CLIENT", clientId, CLIENT_LIMIT, WINDOW);
        var ipDecision =
                rateLimiter.consume(
                        "CLIENT_SECRET_FAILURE_IP",
                        clientNetworkKey.fromRemoteAddress(request.getRemoteAddr()),
                        IP_LIMIT,
                        WINDOW);
        if (!clientDecision.allowed() || !ipDecision.allowed()) {
            OffsetDateTime resetsAt =
                    clientDecision.resetsAt().isAfter(ipDecision.resetsAt())
                            ? clientDecision.resetsAt()
                            : ipDecision.resetsAt();
            writeRateLimited(response, resetsAt);
            LOGGER.warn(
                    "Client authentication failure threshold exceeded: clientId={}, clientFailures={}, ipFailures={}",
                    clientId,
                    clientDecision.count(),
                    ipDecision.count());
            return;
        }
        delegate.onAuthenticationFailure(request, response, exception);
    }

    private static void writeRateLimited(HttpServletResponse response, OffsetDateTime resetsAt)
            throws IOException {
        long retryAfter =
                Math.max(
                        1,
                        Duration.between(OffsetDateTime.now(ZoneOffset.UTC), resetsAt).toSeconds());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"temporarily_unavailable\"}");
    }

    static String clientId(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded =
                        new String(
                                Base64.getDecoder().decode(authorization.substring(6).strip()),
                                StandardCharsets.UTF_8);
                int separator = decoded.indexOf(':');
                if (separator > 0) {
                    return normalizeClientId(
                            URLDecoder.decode(
                                    decoded.substring(0, separator), StandardCharsets.UTF_8));
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed credentials share the unknown-client bucket and the IP bucket.
            }
        }
        String parameter = request.getParameter("client_id");
        return parameter == null || parameter.isBlank()
                ? "<unknown>"
                : normalizeClientId(parameter);
    }

    private static String normalizeClientId(String clientId) {
        String sanitized = clientId.replace('\r', '_').replace('\n', '_');
        return sanitized.length() <= 128 ? sanitized : sanitized.substring(0, 128);
    }
}
