package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class LasSsoSessionControllerTest {

    @Test
    void anonymousSnapshotContainsNoIdentity() {
        var snapshot = LasSsoSessionController.snapshot(null);

        assertThat(snapshot.authenticated()).isFalse();
        assertThat(snapshot.user()).isNull();
    }

    @Test
    void authenticatedSnapshotExposesOnlyStableSafeIdentity() {
        Instant now = Instant.now();
        var idToken = new OidcIdToken("not-returned-to-browser", now, now.plusSeconds(600), Map.of(
                IdTokenClaimNames.ISS, "http://127.0.0.1:9000",
                IdTokenClaimNames.SUB, "019fc000-0000-7000-8000-000000000001",
                IdTokenClaimNames.AUD, List.of("laslog-bff"),
                "preferred_username", "CaseSensitiveUser"));
        var principal = new DefaultOidcUser(List.of(), idToken, "preferred_username");
        var authentication = new OAuth2AuthenticationToken(principal, List.of(), "las-sso");

        var snapshot = LasSsoSessionController.snapshot(authentication);

        assertThat(snapshot.authenticated()).isTrue();
        assertThat(snapshot.user().sub()).isEqualTo("019fc000-0000-7000-8000-000000000001");
        assertThat(snapshot.user().username()).isEqualTo("CaseSensitiveUser");
        assertThat(snapshot.toString()).doesNotContain("not-returned-to-browser");
    }
}
