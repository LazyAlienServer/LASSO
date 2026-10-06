package org.las.sso.client;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class LasSsoSessionRegistrationFilter extends OncePerRequestFilter {

    private final LasSsoSessionRegistration registration;

    LasSsoSessionRegistrationFilter(LasSsoSessionRegistration registration) {
        this.registration = registration;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!registration.register(authentication, request, response)) {
            return;
        }
        filterChain.doFilter(request, response);
    }
}
