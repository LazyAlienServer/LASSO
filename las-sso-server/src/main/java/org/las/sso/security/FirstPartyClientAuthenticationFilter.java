package org.las.sso.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.las.sso.api.ApiProblems;
import org.las.sso.api.RequestIdFilter;
import org.las.sso.identity.UuidV7Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class FirstPartyClientAuthenticationFilter extends OncePerRequestFilter {

    private static final String WWW_AUTHENTICATE =
            "Basic realm=\"LAS SSO internal\", charset=\"UTF-8\"";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(FirstPartyClientAuthenticationFilter.class);
    private static final int MAX_HEADER_LENGTH = 4096;
    private static final int MAX_CLIENT_ID_LENGTH = 128;

    private final RegisteredClientRepository registeredClients;
    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final PathPatternRequestMatcher internalEndpoint;
    private final FirstPartyAuthenticationBudget budget;
    private final ClientNetworkKey networks;
    private final Clock clock;
    private final UuidV7Generator requestIds;
    private final org.las.sso.observability.SsoMetrics metrics;

    public FirstPartyClientAuthenticationFilter(
            RegisteredClientRepository registeredClients,
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            FirstPartyAuthenticationBudget budget,
            ClientNetworkKey networks,
            UuidV7Generator requestIds,
            org.las.sso.observability.SsoMetrics metrics) {
        this(
                registeredClients,
                jdbcClient,
                passwordEncoder,
                budget,
                networks,
                requestIds,
                Clock.systemUTC(),
                metrics);
    }

    FirstPartyClientAuthenticationFilter(
            RegisteredClientRepository registeredClients,
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            FirstPartyAuthenticationBudget budget,
            ClientNetworkKey networks,
            UuidV7Generator requestIds,
            Clock clock,
            org.las.sso.observability.SsoMetrics metrics) {
        this.registeredClients = registeredClients;
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.budget = budget;
        this.networks = networks;
        this.clock = clock;
        this.requestIds = requestIds;
        this.metrics = metrics;
        this.internalEndpoint =
                PathPatternRequestMatcher.pathPattern(
                        "/internal/v1/principals/{subject}/service-access");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !internalEndpoint.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Parse bounded input before reserving; every malformed/missing request still spends
        // anonymous/IP budget.
        Credentials credentials = credentials(request);
        String clientId;
        try {
            var reservation =
                    budget.reserve(
                            credentials == null ? "<unknown>" : credentials.clientId(),
                            networks.fromRemoteAddress(request.getRemoteAddr()));
            clientId = authenticate(credentials);
            if (clientId != null) budget.release(reservation);
        } catch (RateLimitExceededException limited) {
            metrics.internalAuthentication(
                    org.las.sso.observability.SsoMetrics.QueryResult.LIMITED);
            SecurityContextHolder.clearContext();
            long delay =
                    Math.max(
                            1,
                            Duration.between(clock.instant(), limited.resetsAt().toInstant())
                                    .toSeconds());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(delay));
            writeProblem(request, response, 429, "RATE_LIMITED", "请求过于频繁");
            LOGGER.warn("Internal Basic authentication budget exhausted");
            return;
        } catch (DataAccessException | TransactionException unavailable) {
            metrics.internalAuthentication(
                    org.las.sso.observability.SsoMetrics.QueryResult.UNAVAILABLE);
            SecurityContextHolder.clearContext();
            writeProblem(request, response, 503, "IDENTITY_SERVICE_UNAVAILABLE", "身份服务暂时不可用");
            LOGGER.error(
                    "Internal Basic authentication state unavailable: failureType={}",
                    unavailable.getClass().getSimpleName());
            return;
        }
        if (clientId == null) {
            metrics.internalAuthentication(
                    org.las.sso.observability.SsoMetrics.QueryResult.INVALID_CREDENTIALS);
            SecurityContextHolder.clearContext();
            reject(request, response);
            return;
        }
        metrics.internalAuthentication(org.las.sso.observability.SsoMetrics.QueryResult.SUCCESS);
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        clientId,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_FIRST_PARTY_SERVICE")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private Credentials credentials(HttpServletRequest request) {
        var headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (!headers.hasMoreElements()) return null;
        headers.nextElement();
        if (headers.hasMoreElements()) return null;
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.length() > MAX_HEADER_LENGTH) return null;
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String encoded = authorization.substring(6).trim();
        final String decoded;
        try {
            decoded =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(Base64.getDecoder().decode(encoded)))
                            .toString();
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            return null;
        }
        int separator = decoded.indexOf(':');
        if (separator <= 0 || separator == decoded.length() - 1) {
            return null;
        }
        String clientId = decoded.substring(0, separator);
        String clientSecret = decoded.substring(separator + 1);
        if (clientId.isBlank()
                || clientId.length() > MAX_CLIENT_ID_LENGTH
                || clientId.chars().anyMatch(Character::isISOControl)) return null;
        return new Credentials(clientId, clientSecret);
    }

    private String authenticate(Credentials credentials) {
        if (credentials == null) return null;
        String clientId = credentials.clientId();
        String clientSecret = credentials.secret();
        RegisteredClient client = registeredClients.findByClientId(clientId);
        if (client == null
                || client.getClientSecret() == null
                || !client.getClientAuthenticationMethods()
                        .contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)) {
            return null;
        }
        Boolean activeFirstParty =
                jdbcClient
                        .sql(
                                """
                SELECT client_type = 'FIRST_PARTY' AND status = 'ACTIVE'
                  FROM registered_client_metadata
                 WHERE registered_client_id = :registeredClientId
                """)
                        .param("registeredClientId", client.getId())
                        .query(Boolean.class)
                        .optional()
                        .orElse(false);
        return Boolean.TRUE.equals(activeFirstParty)
                        && passwordMatches(clientSecret, client.getClientSecret())
                ? clientId
                : null;
    }

    private boolean passwordMatches(String rawSecret, String encodedSecret) {
        try {
            return passwordEncoder.matches(rawSecret, encodedSecret);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE);
        ApiProblems.write(request, response, 401, "CLIENT_AUTHENTICATION_REQUIRED", "客户端认证失败");
    }

    private void writeProblem(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String title)
            throws IOException {
        Object existingId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        UUID requestId = existingId instanceof UUID id ? id : requestIds.generate();
        request.setAttribute(RequestIdFilter.ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId.toString());
        ApiProblems.write(request, response, status, code, title);
    }

    private record Credentials(String clientId, String secret) {}
}
