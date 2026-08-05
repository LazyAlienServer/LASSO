package org.las.sso.security;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.las.sso.config.LoginRiskProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class LoginRiskAuthenticationFilter extends OncePerRequestFilter {

    private final PostgresRateLimiter rateLimiter;
    private final ClientNetworkKey clientNetworkKey;
    private final AltchaChallengeService captcha;
    private final LoginRiskProperties properties;

    public LoginRiskAuthenticationFilter(
            PostgresRateLimiter rateLimiter,
            ClientNetworkKey clientNetworkKey,
            AltchaChallengeService captcha,
            LoginRiskProperties properties) {
        this.rateLimiter = rateLimiter;
        this.clientNetworkKey = clientNetworkKey;
        this.captcha = captcha;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !(request.getContextPath() + "/login").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String networkKey = clientNetworkKey.fromRemoteAddress(request.getRemoteAddr());
        var blocked = rateLimiter.current(
                "LOGIN_FAILURE_IP_BLOCK",
                networkKey,
                properties.blockIpFailures(),
                properties.blockWindow());
        if (blocked.thresholdReached()) {
            writeRateLimited(response, blocked.resetsAt());
            return;
        }

        var captchaRequired = rateLimiter.current(
                "LOGIN_FAILURE_IP_CAPTCHA",
                networkKey,
                properties.captchaIpFailures(),
                properties.captchaWindow());
        if (!captchaRequired.thresholdReached()) {
            filterChain.doFilter(request, response);
            return;
        }

        String payload = request.getParameter("captchaPayload");
        if (payload == null || payload.isBlank()) {
            response.sendRedirect(request.getContextPath() + "/login?captcha");
            return;
        }
        try {
            captcha.verifyAndConsume(AltchaChallengeService.Purpose.LOGIN_RISK, payload, networkKey);
            filterChain.doFilter(request, response);
        } catch (CaptchaInvalidException exception) {
            response.sendRedirect(request.getContextPath() + "/login?captcha");
        }
    }

    private void writeRateLimited(HttpServletResponse response, OffsetDateTime resetsAt)
            throws IOException {
        long retryAfter = Math.max(1, Duration.between(
                OffsetDateTime.now(ZoneOffset.UTC), resetsAt).toSeconds());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("登录尝试过于频繁，请稍后重试。");
    }
}
