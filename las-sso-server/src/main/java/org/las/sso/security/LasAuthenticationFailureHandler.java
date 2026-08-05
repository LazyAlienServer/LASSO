package org.las.sso.security;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.las.sso.config.LoginRiskProperties;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public final class LasAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final LoginAttemptService loginAttempts;
    private final PostgresRateLimiter rateLimiter;
    private final ClientNetworkKey clientNetworkKey;
    private final LoginRiskProperties properties;
    private final LoginFailureResponseJitter failureJitter;

    public LasAuthenticationFailureHandler(
            LoginAttemptService loginAttempts,
            PostgresRateLimiter rateLimiter,
            ClientNetworkKey clientNetworkKey,
            LoginRiskProperties properties,
            LoginFailureResponseJitter failureJitter) {
        this.loginAttempts = loginAttempts;
        this.rateLimiter = rateLimiter;
        this.clientNetworkKey = clientNetworkKey;
        this.properties = properties;
        this.failureJitter = failureJitter;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        String username = request.getParameter("username");
        if (username != null && !username.isBlank()) {
            loginAttempts.recordFailure(username);
        }
        var ipLimit = rateLimiter.consume(
                "LOGIN_FAILURE_IP_BLOCK",
                clientNetworkKey.fromRemoteAddress(request.getRemoteAddr()),
                properties.blockIpFailures(),
                properties.blockWindow());
        rateLimiter.consume(
                "LOGIN_FAILURE_IP_CAPTCHA",
                clientNetworkKey.fromRemoteAddress(request.getRemoteAddr()),
                properties.captchaIpFailures(),
                properties.captchaWindow());
        if (!ipLimit.allowed()) {
            long retryAfter = Math.max(1, Duration.between(
                    OffsetDateTime.now(ZoneOffset.UTC), ipLimit.resetsAt()).toSeconds());
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("登录尝试过于频繁，请稍后重试。");
            return;
        }
        failureJitter.redirect(request, response, request.getContextPath() + "/login?error");
    }
}
