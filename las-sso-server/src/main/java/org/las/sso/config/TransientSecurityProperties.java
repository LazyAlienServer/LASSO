package org.las.sso.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("las.sso.transient-security")
public record TransientSecurityProperties(
        @NotBlank String keyPepper,
        @NotBlank String captchaHmacSecret) {
}
