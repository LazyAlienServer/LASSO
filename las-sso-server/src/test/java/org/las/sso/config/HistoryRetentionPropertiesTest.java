package org.las.sso.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HistoryRetentionPropertiesTest {
    @Test
    void minimumsAndBatchBoundariesCannotBeBypassed() {
        assertThatCode(() -> settings(100, 90, 30)).doesNotThrowAnyException();
        assertThatThrownBy(() -> settings(1001, 90, 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(0, 90, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(100, 89, 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(100, 90, 29))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(100, 3651, 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private HistoryRetentionProperties settings(int batch, int sessions, int tokens) {
        return new HistoryRetentionProperties(
                batch,
                Duration.ofDays(sessions),
                Duration.ofDays(90),
                Duration.ofDays(tokens),
                Duration.ofDays(30),
                Duration.ofDays(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5));
    }

    @Test
    void schedulerConfigurationMustBePositiveAndCannotTightLoopOrSleepIndefinitely() {
        assertThatThrownBy(
                        () ->
                                new HistoryRetentionProperties(
                                        100,
                                        Duration.ofDays(90),
                                        Duration.ofDays(90),
                                        Duration.ofDays(30),
                                        Duration.ofDays(30),
                                        Duration.ofDays(30),
                                        Duration.ZERO,
                                        Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new HistoryRetentionProperties(
                                        100,
                                        Duration.ofDays(90),
                                        Duration.ofDays(90),
                                        Duration.ofDays(30),
                                        Duration.ofDays(30),
                                        Duration.ofDays(30),
                                        Duration.ofMinutes(5),
                                        Duration.ofDays(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new HistoryRetentionProperties(
                                        100,
                                        Duration.ofHours(2161),
                                        Duration.ofDays(90),
                                        Duration.ofDays(30),
                                        Duration.ofDays(30),
                                        Duration.ofDays(30),
                                        Duration.ofMinutes(5),
                                        Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
