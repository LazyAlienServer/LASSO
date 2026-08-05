package org.las.sso.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginFailureResponseJitterTest {

    @Test
    void schedulesRedirectWithoutBlockingRequestThread() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        doReturn(scheduledFuture).when(scheduler).schedule(
                any(Runnable.class), eq(150L), eq(TimeUnit.MILLISECONDS));
        var jitter = new LoginFailureResponseJitter(
                scheduler, Duration.ofMillis(100), Duration.ofMillis(200), () -> 50L);
        var request = new MockHttpServletRequest();
        request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();

        jitter.redirect(request, response, "/login?error");

        assertThat(request.isAsyncStarted()).isTrue();
        assertThat(response.getRedirectedUrl()).isNull();
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), eq(150L), eq(TimeUnit.MILLISECONDS));

        task.getValue().run();
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(request.isAsyncStarted()).isFalse();
    }
}
