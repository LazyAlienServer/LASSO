package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class LasSsoBackChannelLogoutControllerTest {

    private static final String EVENT =
            "http://schemas.openid.net/event/backchannel-logout";

    @Test
    void validLogoutTokenInvalidatesRegisteredSessionAndAllowsIdenticalReplay() {
        var registry = new LasSsoBackChannelSessionRegistry();
        var session = new MockHttpSession();
        registry.register("sid-1", "subject-1", session);
        Jwt jwt = logoutToken("token-1", "jti-1", "client-1");
        JwtDecoder decoder = token -> jwt;
        var controller = new LasSsoBackChannelLogoutController(decoder, "client-1", registry);

        assertThat(controller.logout("token-1").getStatusCode().value()).isEqualTo(204);
        assertThat(session.isInvalid()).isTrue();
        assertThat(controller.logout("token-1").getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void sameJtiWithDifferentTokenAndWrongAudienceAreRejected() {
        var registry = new LasSsoBackChannelSessionRegistry();
        Jwt valid = logoutToken("token-1", "jti-1", "client-1");
        JwtDecoder decoder = token -> "token-1".equals(token)
                ? valid
                : logoutToken(token, "jti-1", "client-1");
        var controller = new LasSsoBackChannelLogoutController(decoder, "client-1", registry);

        assertThat(controller.logout("token-1").getStatusCode().value()).isEqualTo(204);
        assertThat(controller.logout("different-token").getStatusCode().value()).isEqualTo(400);

        var wrongAudience = new LasSsoBackChannelLogoutController(
                token -> logoutToken(token, "jti-2", "other-client"),
                "client-1", registry);
        assertThat(wrongAudience.logout("token-2").getStatusCode().value()).isEqualTo(400);
    }

    private static Jwt logoutToken(String token, String jti, String audience) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .header("typ", "logout+jwt")
                .issuer("http://issuer.example")
                .subject("subject-1")
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(120))
                .claim("jti", jti)
                .claim("sid", "sid-1")
                .claim("events", Map.of(EVENT, Map.of()))
                .build();
    }
}
