package org.las.sso.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

@Configuration
@Profile("!local & !test")
class PersistentSigningKeyConfiguration {

    @Bean
    @Lazy(false)
    JWKSource<SecurityContext> persistentJwkSource(LasSsoProperties properties) {
        var signingKey = properties.signingKey();
        if (!StringUtils.hasText(signingKey.path())
                || !StringUtils.hasText(signingKey.password())
                || !StringUtils.hasText(signingKey.alias())) {
            throw new IllegalStateException(
                    "Production signing key path, password and alias are required");
        }

        try (InputStream input = Files.newInputStream(Path.of(signingKey.path()))) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            char[] password = signingKey.password().toCharArray();
            try {
                keyStore.load(input, password);
                var privateKey = keyStore.getKey(signingKey.alias(), password);
                var certificate = keyStore.getCertificate(signingKey.alias());
                if (!(privateKey instanceof RSAPrivateKey rsaPrivateKey)
                        || certificate == null
                        || !(certificate.getPublicKey() instanceof RSAPublicKey rsaPublicKey)
                        || rsaPublicKey.getModulus().bitLength() < 2048
                        || !rsaPrivateKey.getModulus().equals(rsaPublicKey.getModulus())) {
                    throw new IllegalStateException(
                            "Configured signing key must be a matching RSA pair of at least 2048 bits");
                }
                var rsaKey =
                        new RSAKey.Builder(rsaPublicKey)
                                .privateKey(rsaPrivateKey)
                                .keyID(signingKey.keyId())
                                .build();
                return new ImmutableJWKSet<>(new JWKSet(rsaKey));
            } finally {
                Arrays.fill(password, '\0');
            }
        } catch (Exception exception) {
            // Keystore/provider failures can contain external path/alias data; expose only a
            // category.
            throw new IllegalStateException("Unable to load persistent signing key");
        }
    }
}
