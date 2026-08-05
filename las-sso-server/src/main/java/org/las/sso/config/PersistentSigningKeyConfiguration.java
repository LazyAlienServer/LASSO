package org.las.sso.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

@Configuration
@Profile("!local & !test")
class PersistentSigningKeyConfiguration {

    @Bean
    JWKSource<SecurityContext> persistentJwkSource(LasSsoProperties properties) {
        var signingKey = properties.signingKey();
        if (!StringUtils.hasText(signingKey.path())
                || !StringUtils.hasText(signingKey.password())
                || !StringUtils.hasText(signingKey.alias())) {
            throw new IllegalStateException("Production signing key path, password and alias are required");
        }

        try (InputStream input = Files.newInputStream(Path.of(signingKey.path()))) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            char[] password = signingKey.password().toCharArray();
            keyStore.load(input, password);
            var privateKey = keyStore.getKey(signingKey.alias(), password);
            var certificate = keyStore.getCertificate(signingKey.alias());
            if (!(privateKey instanceof RSAPrivateKey rsaPrivateKey)
                    || !(certificate.getPublicKey() instanceof RSAPublicKey rsaPublicKey)) {
                throw new IllegalStateException("Configured signing key must be an RSA key pair");
            }
            var rsaKey = new RSAKey.Builder(rsaPublicKey)
                    .privateKey(rsaPrivateKey)
                    .keyID(signingKey.keyId())
                    .build();
            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        }
        catch (Exception exception) {
            throw new IllegalStateException("Unable to load persistent signing key", exception);
        }
    }
}
