package org.las.sso.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.las.sso.config.LoginRiskProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

@Component
public final class LoginRiskAuthenticationFilter extends OncePerRequestFilter {

    private final PostgresRateLimiter rateLimiter;
    private final ClientNetworkKey clientNetworkKey;
    private final AltchaChallengeService captcha;
    private final LoginRiskProperties properties;
    private final org.las.sso.observability.SsoMetrics metrics;

    public LoginRiskAuthenticationFilter(
            PostgresRateLimiter rateLimiter,
            ClientNetworkKey clientNetworkKey,
            AltchaChallengeService captcha,
            LoginRiskProperties properties,
            org.las.sso.observability.SsoMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.clientNetworkKey = clientNetworkKey;
        this.captcha = captcha;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        try {
            return !"/login".equals(UriUtils.decode(path, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String networkKey = clientNetworkKey.fromRemoteAddress(request.getRemoteAddr());
        var blocked =
                rateLimiter.current(
                        "LOGIN_FAILURE_IP_BLOCK",
                        networkKey,
                        properties.blockIpFailures(),
                        properties.blockWindow());
        if (blocked.thresholdReached()) {
            metrics.login(
                    org.las.sso.observability.SsoMetrics.LoginResult.LIMITED,
                    org.las.sso.observability.SsoMetrics.LoginReason.NETWORK);
            writeRateLimited(response, blocked.resetsAt());
            return;
        }

        var captchaRequired =
                rateLimiter.current(
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
            metrics.login(
                    org.las.sso.observability.SsoMetrics.LoginResult.CAPTCHA_REQUIRED,
                    org.las.sso.observability.SsoMetrics.LoginReason.CAPTCHA);
            response.sendRedirect(request.getContextPath() + "/login?captcha");
            return;
        }
        try {
            captcha.verifyAndConsume(
                    AltchaChallengeService.Purpose.LOGIN_RISK, payload, networkKey);
            filterChain.doFilter(request, response);
        } catch (CaptchaInvalidException exception) {
            metrics.login(
                    org.las.sso.observability.SsoMetrics.LoginResult.CAPTCHA_REQUIRED,
                    org.las.sso.observability.SsoMetrics.LoginReason.CAPTCHA);
            response.sendRedirect(request.getContextPath() + "/login?captcha");
        }
    }

    private void writeRateLimited(HttpServletResponse response, OffsetDateTime resetsAt)
            throws IOException {
        long retryAfter =
                Math.max(
                        1,
                        Duration.between(OffsetDateTime.now(ZoneOffset.UTC), resetsAt).toSeconds());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("登录尝试过于频繁，请稍后重试。");
    }
}
