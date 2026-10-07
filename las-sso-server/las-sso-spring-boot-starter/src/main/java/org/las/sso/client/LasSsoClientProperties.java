package org.las.sso.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("las.sso.client")
public record LasSsoClientProperties(
        String registrationId,
        Duration refreshBeforeExpiry,
        String postLogoutRedirectUri,
        String logoutSuccessUri,
        Duration logoutStateTtl,
        String sessionCookieName,
        Duration sessionAbsoluteLifetime) {

    public LasSsoClientProperties(String registrationId, Duration refreshBeforeExpiry) {
        this(registrationId, refreshBeforeExpiry, null, null, null, null, null);
    }

    public LasSsoClientProperties(
            String registrationId,
            Duration refreshBeforeExpiry,
            String postLogoutRedirectUri,
            String logoutSuccessUri,
            Duration logoutStateTtl) {
        this(
                registrationId,
                refreshBeforeExpiry,
                postLogoutRedirectUri,
                logoutSuccessUri,
                logoutStateTtl,
                null,
                null);
    }

    @ConstructorBinding
    public LasSsoClientProperties {
        registrationId =
                registrationId == null || registrationId.isBlank() ? "las-sso" : registrationId;
        refreshBeforeExpiry =
                refreshBeforeExpiry == null ? Duration.ofSeconds(60) : refreshBeforeExpiry;
        postLogoutRedirectUri =
                postLogoutRedirectUri == null || postLogoutRedirectUri.isBlank()
                        ? "{baseUrl}/logged-out"
                        : postLogoutRedirectUri;
        logoutSuccessUri =
                logoutSuccessUri == null || logoutSuccessUri.isBlank() ? "/" : logoutSuccessUri;
        logoutStateTtl = logoutStateTtl == null ? Duration.ofMinutes(5) : logoutStateTtl;
        sessionCookieName =
                sessionCookieName == null || sessionCookieName.isBlank()
                        ? "JSESSIONID"
                        : sessionCookieName;
        sessionAbsoluteLifetime =
                sessionAbsoluteLifetime == null ? Duration.ofDays(14) : sessionAbsoluteLifetime;
        if (refreshBeforeExpiry.isNegative()) {
            throw new IllegalArgumentException("refreshBeforeExpiry must not be negative");
        }
        if (!logoutSuccessUri.startsWith("/") || logoutSuccessUri.startsWith("//")) {
            throw new IllegalArgumentException("logoutSuccessUri must be a local absolute path");
        }
        if (logoutStateTtl.isZero()
                || logoutStateTtl.isNegative()
                || logoutStateTtl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("logoutStateTtl must be between 0 and 15 minutes");
        }
        if (!sessionCookieName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("sessionCookieName is not a valid cookie name");
        }
        if (sessionAbsoluteLifetime.isZero()
                || sessionAbsoluteLifetime.isNegative()
                || sessionAbsoluteLifetime.compareTo(Duration.ofDays(14)) > 0) {
            throw new IllegalArgumentException(
                    "sessionAbsoluteLifetime must be between 0 and 14 days");
        }
    }
}
