package com.kibo.reservation.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class HoldExpirationJobTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:10:00Z");

    private final HoldExpirationService service = mock(HoldExpirationService.class);
    private final HoldExpirationJob job = new HoldExpirationJob(service, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void eachRunSweepsOverdueHoldsAsOfTheCurrentTime() {
        when(service.expireOverdue(NOW)).thenReturn(new HoldExpirationService.Summary(3, 3, 0));

        job.run();

        verify(service).expireOverdue(NOW);
    }

    @Test
    void aFailingSweepNeverEscapesSoTheScheduleKeepsRunning() {
        when(service.expireOverdue(NOW)).thenThrow(new IllegalStateException("database down"));

        assertThatCode(job::run).doesNotThrowAnyException();
    }

    @Test
    void isScheduledFromTheConfiguredIntervalWithFixedDelayAndNoOverlap() throws Exception {
        Method run = HoldExpirationJob.class.getMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);

        org.assertj.core.api.Assertions.assertThat(scheduled).isNotNull();
        org.assertj.core.api.Assertions.assertThat(scheduled.fixedDelayString()).isEqualTo("${kibo.expiration.interval}");
        org.assertj.core.api.Assertions.assertThat(scheduled.initialDelayString()).isEqualTo("${kibo.expiration.interval}");
    }
}
