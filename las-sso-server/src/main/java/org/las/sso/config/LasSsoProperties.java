package org.las.sso.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("las.sso")
public record LasSsoProperties(@NotNull URI issuer, @Valid SigningKey signingKey) {

    public record SigningKey(String path, String password, String alias, @NotBlank String keyId) {}
}
