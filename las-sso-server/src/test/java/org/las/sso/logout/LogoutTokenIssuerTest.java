package org.las.sso.logout;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.las.sso.config.LasSsoProperties;
import org.las.sso.identity.UuidV7Generator;

import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class LogoutTokenIssuerTest {

    @Test
    void createsSignedOidcBackChannelLogoutTokenWithoutNonce() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var publicKey = (RSAPublicKey) pair.getPublic();
        var rsaKey = new RSAKey.Builder(publicKey)
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID("test-key")
                .build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
        var properties = new LasSsoProperties(
                java.net.URI.create("https://auth.las.example"),
                new LasSsoProperties.SigningKey(null, null, null, "test-key"));
        var issuer = new LogoutTokenIssuer(
                encoder,
                new UuidV7Generator(),
                properties,
                Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC));

        String token = issuer.issue(
                "laslog-bff",
                "019c10a5-b800-7000-8000-000000000001",
                "session-123");

        var jwt = SignedJWT.parse(token);
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
        var claims = jwt.getJWTClaimsSet();
        assertThat(jwt.getHeader().getType().toString()).isEqualTo("logout+jwt");
        assertThat(claims.getIssuer()).isEqualTo("https://auth.las.example");
        assertThat(claims.getAudience()).containsExactly("laslog-bff");
        assertThat(claims.getSubject()).isEqualTo("019c10a5-b800-7000-8000-000000000001");
        assertThat(claims.getStringClaim("sid")).isEqualTo("session-123");
        assertThat(claims.getClaims()).doesNotContainKey("nonce");
        assertThat(claims.getJSONObjectClaim("events"))
                .containsEntry(LogoutTokenIssuer.BACK_CHANNEL_LOGOUT_EVENT, Map.of());
        assertThat(claims.getExpirationTime().toInstant())
                .isEqualTo(Instant.parse("2026-08-02T00:02:00Z"));
    }
}
