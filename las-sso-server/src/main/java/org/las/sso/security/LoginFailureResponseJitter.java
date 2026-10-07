package org.las.sso.security;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.las.sso.config.LoginRiskProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class LoginFailureResponseJitter {

    private final ScheduledExecutorService scheduler;
    private final Duration minimum;
    private final Duration maximum;
    private final LongSupplier randomOffset;

    @Autowired
    public LoginFailureResponseJitter(
            ScheduledExecutorService scheduler, LoginRiskProperties properties) {
        this(
                scheduler,
                properties.jitterMin(),
                properties.jitterMax(),
                () -> ThreadLocalRandom.current().nextLong());
    }

    LoginFailureResponseJitter(
            ScheduledExecutorService scheduler,
            Duration minimum,
            Duration maximum,
            LongSupplier randomOffset) {
        this.scheduler = scheduler;
        this.minimum = minimum;
        this.maximum = maximum;
        this.randomOffset = randomOffset;
    }

    public void redirect(HttpServletRequest request, HttpServletResponse response, String location)
            throws IOException {
        long delayMillis = delayMillis();
        if (delayMillis == 0 || !request.isAsyncSupported()) {
            response.sendRedirect(location);
            return;
        }

        AsyncContext async = request.startAsync();
        async.setTimeout(maximum.toMillis() + 5_000);
        scheduler.schedule(
                () -> {
                    try {
                        response.sendRedirect(location);
                    } catch (IOException exception) {
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    } finally {
                        async.complete();
                    }
                },
                delayMillis,
                TimeUnit.MILLISECONDS);
    }

    private long delayMillis() {
        long minMillis = minimum.toMillis();
        long maxMillis = maximum.toMillis();
        if (minMillis < 0 || maxMillis < minMillis) {
            throw new IllegalStateException("Invalid login failure jitter range");
        }
        long range = maxMillis - minMillis;
        return range == 0
                ? minMillis
                : minMillis + Math.floorMod(randomOffset.getAsLong(), range + 1);
    }
}
