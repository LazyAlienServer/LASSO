package org.las.sso.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAuthenticationRateLimitConverterTest {

    @Test
    void rejectsBeforeAuthenticationWhenClientBucketReachedThreshold() {
        var limiter = mock(PostgresRateLimiter.class);
        var networkKey = mock(ClientNetworkKey.class);
        var reset = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);
        when(networkKey.fromRemoteAddress("192.0.2.10")).thenReturn("network-key");
        when(limiter.current(eq("CLIENT_SECRET_FAILURE_CLIENT"), eq("client-a"), eq(10), any()))
                .thenReturn(new PostgresRateLimiter.Observation(10, 10, reset));
        when(limiter.current(eq("CLIENT_SECRET_FAILURE_IP"), eq("network-key"), eq(20), any()))
                .thenReturn(new PostgresRateLimiter.Observation(1, 20, reset));
        var converter = new ClientAuthenticationRateLimitConverter(limiter, networkKey);
        var request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setRemoteAddr("192.0.2.10");
        request.addParameter("client_id", "client-a");

        assertThatThrownBy(() -> converter.convert(request))
                .isInstanceOf(ClientAuthenticationRateLimitException.class)
                .extracting("resetsAt")
                .isEqualTo(reset);
    }
}
