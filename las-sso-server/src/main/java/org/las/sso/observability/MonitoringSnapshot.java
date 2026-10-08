package org.las.sso.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.las.sso.config.MonitoringProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Independently scheduled, bounded SQL observation. A scrape only reads the cached immutable
 * snapshot.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "las.sso.monitoring.enabled", havingValue = "true")
public final class MonitoringSnapshot {

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final MonitoringProperties properties;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("sso-monitoring-snapshot-").factory());
    private volatile Snapshot current = new Snapshot(0, 0, 0, 0, 0);
    private volatile double lastSuccess;
    private volatile double available;
    private final AtomicBoolean started = new AtomicBoolean();

    public MonitoringSnapshot(
            DataSource dataSource, MeterRegistry registry, MonitoringProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(2);
        this.registry = registry;
        this.properties = properties;
        Gauge.builder("las.sso.sessions.active", this, value -> value.current.activeSessions())
                .register(registry);
        Gauge.builder("las.sso.outbox.pending", this, value -> value.current.pending())
                .register(registry);
        Gauge.builder("las.sso.outbox.dead", this, value -> value.current.dead())
                .register(registry);
        Gauge.builder("las.sso.outbox.expired.claims", this, value -> value.current.expiredClaims())
                .register(registry);
        Gauge.builder("las.sso.outbox.oldest.age", this, value -> value.current.oldestAge())
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("las.sso.snapshot.last.success", this, value -> value.lastSuccess)
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("las.sso.snapshot.available", this, value -> value.available)
                .register(registry);
        registry.counter("las.sso.snapshot.failures");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(
                    this::refresh,
                    0,
                    properties.snapshotInterval().toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    public void refresh() {
        try {
            Snapshot fresh =
                    jdbc.queryForObject(
                            """
                    SELECT (SELECT count(*) FROM user_sessions
                              WHERE status = 'ACTIVE' AND LEAST(idle_expires_at, absolute_expires_at) > CURRENT_TIMESTAMP) AS active_sessions,
                           count(*) FILTER (WHERE processed_at IS NULL AND dead_lettered_at IS NULL) AS pending,
                           count(*) FILTER (WHERE dead_lettered_at IS NOT NULL) AS dead,
                           count(*) FILTER (WHERE processed_at IS NULL AND dead_lettered_at IS NULL
                             AND claimed_at < CURRENT_TIMESTAMP - INTERVAL '2 minutes') AS expired_claims,
                           COALESCE(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - min(created_at)
                             FILTER (WHERE processed_at IS NULL AND dead_lettered_at IS NULL)), 0) AS oldest_age
                      FROM outbox_events
                     WHERE processed_at IS NULL AND event_type IN ('BACKCHANNEL_LOGOUT_REQUESTED', 'GLOBAL_LOGOUT_REQUESTED')
                    """,
                            (rs, row) ->
                                    new Snapshot(
                                            rs.getLong("active_sessions"),
                                            rs.getLong("pending"),
                                            rs.getLong("dead"),
                                            rs.getLong("expired_claims"),
                                            Math.max(0, rs.getDouble("oldest_age"))));
            current = fresh;
            lastSuccess = Instant.now().getEpochSecond();
            available = 1;
        } catch (RuntimeException unavailable) {
            available = 0;
            registry.counter("las.sso.snapshot.failures").increment();
            // Last-known values remain; availability/freshness explicitly invalidate them. No
            // exception/SQL/config text logged.
        }
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private record Snapshot(
            long activeSessions, long pending, long dead, long expiredClaims, double oldestAge) {}
}
