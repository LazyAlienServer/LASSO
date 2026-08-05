package org.las.sso.logout;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.las.sso.config.LasSsoProperties;
import org.las.sso.identity.UuidV7Generator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithms;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public final class LogoutTokenIssuer {

    public static final String BACK_CHANNEL_LOGOUT_EVENT =
            "http://schemas.openid.net/event/backchannel-logout";

    private final JwtEncoder jwtEncoder;
    private final UuidV7Generator uuidGenerator;
    private final LasSsoProperties properties;
    private final Clock clock;

    @Autowired
    public LogoutTokenIssuer(
            JwtEncoder jwtEncoder,
            UuidV7Generator uuidGenerator,
            LasSsoProperties properties) {
        this(jwtEncoder, uuidGenerator, properties, Clock.systemUTC());
    }

    LogoutTokenIssuer(
            JwtEncoder jwtEncoder,
            UuidV7Generator uuidGenerator,
            LasSsoProperties properties,
            Clock clock) {
        this.jwtEncoder = Objects.requireNonNull(jwtEncoder);
        this.uuidGenerator = Objects.requireNonNull(uuidGenerator);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public String issue(String clientId, String subject, String sid) {
        if ((subject == null || subject.isBlank()) && (sid == null || sid.isBlank())) {
            throw new IllegalArgumentException("Logout Token requires subject or sid");
        }

        var now = clock.instant();
        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer().toString())
                .audience(java.util.List.of(clientId))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(2)))
                .id(uuidGenerator.generate().toString())
                .claim("events", Map.of(BACK_CHANNEL_LOGOUT_EVENT, Map.of()));
        if (subject != null && !subject.isBlank()) {
            claims.subject(subject);
        }
        if (sid != null && !sid.isBlank()) {
            claims.claim("sid", sid);
        }

        var headers = JwsHeader.with(() -> JwsAlgorithms.RS256)
                .type("logout+jwt")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims.build())).getTokenValue();
    }
}
