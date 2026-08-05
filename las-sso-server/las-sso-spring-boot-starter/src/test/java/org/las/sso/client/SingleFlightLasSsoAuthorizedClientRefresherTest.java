package org.las.sso.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

class SingleFlightLasSsoAuthorizedClientRefresherTest {

    @Test
    void passesTheCurrentServletRequestAndResponseToTheAuthorizedClientManager() {
        var manager = mock(OAuth2AuthorizedClientManager.class);
        var captured = new AtomicReference<OAuth2AuthorizeRequest>();
        when(manager.authorize(any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return mock(OAuth2AuthorizedClient.class);
        });
        var refresher = new SingleFlightLasSsoAuthorizedClientRefresher(
                manager, new LasSsoClientProperties("las-sso", null));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "user-id", null, java.util.List.of());
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        refresher.refresh(authentication, request, response);

        assertThat(captured.get().getClientRegistrationId()).isEqualTo("las-sso");
        assertThat(captured.get().getPrincipal()).isSameAs(authentication);
        HttpServletRequest capturedRequest = captured.get()
                .getAttribute(HttpServletRequest.class.getName());
        HttpServletResponse capturedResponse = captured.get()
                .getAttribute(HttpServletResponse.class.getName());
        assertThat(capturedRequest).isSameAs(request);
        assertThat(capturedResponse).isSameAs(response);
    }

    @Test
    void concurrentRequestsForTheSameSessionShareOneRefreshAttempt() throws Exception {
        var manager = mock(OAuth2AuthorizedClientManager.class);
        var bothRequestsHaveAKey = new CountDownLatch(2);
        when(manager.authorize(any())).thenAnswer(invocation -> {
            assertThat(bothRequestsHaveAKey.await(5, TimeUnit.SECONDS)).isTrue();
            return mock(OAuth2AuthorizedClient.class);
        });
        var refresher = new SingleFlightLasSsoAuthorizedClientRefresher(
                manager, new LasSsoClientProperties("las-sso", null));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "user-id", null, java.util.List.of());
        var session = new CoordinatedSession(bothRequestsHaveAKey);
        var firstRequest = new MockHttpServletRequest();
        firstRequest.setSession(session);
        var secondRequest = new MockHttpServletRequest();
        secondRequest.setSession(session);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> refresher.refresh(
                    authentication, firstRequest, new MockHttpServletResponse()));
            var second = executor.submit(() -> refresher.refresh(
                    authentication, secondRequest, new MockHttpServletResponse()));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        verify(manager, times(1)).authorize(any());
    }

    private static final class CoordinatedSession extends MockHttpSession {

        private final CountDownLatch keyReads;

        private CoordinatedSession(CountDownLatch keyReads) {
            this.keyReads = keyReads;
        }

        @Override
        public String getId() {
            keyReads.countDown();
            return super.getId();
        }
    }
}
