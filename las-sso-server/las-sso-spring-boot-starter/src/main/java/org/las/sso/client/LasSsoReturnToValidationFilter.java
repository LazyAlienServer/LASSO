package org.las.sso.client;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Invalid direct authorization requests must be 400, not Spring's generic redirect-failure 500. */
final class LasSsoReturnToValidationFilter extends OncePerRequestFilter {

    private final RequestMatcher endpoint;

    LasSsoReturnToValidationFilter(LasSsoClientProperties properties) {
        endpoint = PathPatternRequestMatcher.pathPattern("/oauth2/authorization/" + properties.registrationId());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (endpoint.matches(request)) {
            try {
                LasSsoReturnTo.parameter(request);
            } catch (IllegalArgumentException exception) {
                response.setStatus(400);
                response.setHeader("Cache-Control", "no-store");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
