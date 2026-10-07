package org.las.sso.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class ClientAuthenticationFailureHandlerTest {

    @Test
    void blocksExceededClientWithoutIncludingSecretInRateLimitKey() throws Exception {
        var limiter = mock(PostgresRateLimiter.class);
        var networkKey = mock(ClientNetworkKey.class);
        var reset = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);
        when(networkKey.fromRemoteAddress("192.0.2.10")).thenReturn("network-key");
        when(limiter.consume(eq("CLIENT_SECRET_FAILURE_CLIENT"), eq("client-a"), eq(10), any()))
                .thenReturn(new PostgresRateLimiter.Decision(false, 11, 10, reset));
        when(limiter.consume(eq("CLIENT_SECRET_FAILURE_IP"), eq("network-key"), eq(20), any()))
                .thenReturn(new PostgresRateLimiter.Decision(true, 1, 20, reset));
        var handler = new ClientAuthenticationFailureHandler(limiter, networkKey);
        var request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader(
                "Authorization",
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        "client-a:do-not-store-this-secret"
                                                .getBytes(StandardCharsets.UTF_8)));
        var response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                request, response, new BadCredentialsException("bad client secret"));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\":\"temporarily_unavailable\"}");
        verify(limiter).consume(eq("CLIENT_SECRET_FAILURE_CLIENT"), eq("client-a"), eq(10), any());
    }
}
