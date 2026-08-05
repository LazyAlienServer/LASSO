package org.las.sso.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("las.sso.recovery")
public record RecoveryProperties(@NotBlank String pepper) {
}
