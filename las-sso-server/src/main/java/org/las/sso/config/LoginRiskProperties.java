package org.las.sso.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("las.sso.login-risk")
public record LoginRiskProperties(
        @Min(1) @DefaultValue("20") int captchaIpFailures,
        @Min(1) @DefaultValue("50") int blockIpFailures,
        @NotNull @DefaultValue("PT15M") Duration captchaWindow,
        @NotNull @DefaultValue("PT30M") Duration blockWindow,
        @NotNull @DefaultValue("PT0.15S") Duration jitterMin,
        @NotNull @DefaultValue("PT0.45S") Duration jitterMax) {
}
