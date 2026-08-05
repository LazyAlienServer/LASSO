package org.las.sso.api;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.las.sso.identity.UuidV7Generator;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private final UuidV7Generator uuidGenerator;

    public RequestIdFilter(UuidV7Generator uuidGenerator) {
        this.uuidGenerator = uuidGenerator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID requestId = parseOrGenerate(request.getHeader("X-Request-Id"));
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId.toString());
        filterChain.doFilter(request, response);
    }

    private UUID parseOrGenerate(String value) {
        if (value != null && value.length() <= 36) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // Generate a server-owned request ID below.
            }
        }
        return uuidGenerator.generate();
    }
}
