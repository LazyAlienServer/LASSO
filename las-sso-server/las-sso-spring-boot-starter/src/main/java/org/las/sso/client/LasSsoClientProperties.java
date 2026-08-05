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
        Duration logoutStateTtl) {

    public LasSsoClientProperties(String registrationId, Duration refreshBeforeExpiry) {
        this(registrationId, refreshBeforeExpiry, null, null, null);
    }

    @ConstructorBinding
    public LasSsoClientProperties {
        registrationId = registrationId == null || registrationId.isBlank()
                ? "las-sso"
                : registrationId;
        refreshBeforeExpiry = refreshBeforeExpiry == null
                ? Duration.ofSeconds(60)
                : refreshBeforeExpiry;
        postLogoutRedirectUri = postLogoutRedirectUri == null || postLogoutRedirectUri.isBlank()
                ? "{baseUrl}/logged-out"
                : postLogoutRedirectUri;
        logoutSuccessUri = logoutSuccessUri == null || logoutSuccessUri.isBlank()
                ? "/"
                : logoutSuccessUri;
        logoutStateTtl = logoutStateTtl == null ? Duration.ofMinutes(5) : logoutStateTtl;
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
    }
}
