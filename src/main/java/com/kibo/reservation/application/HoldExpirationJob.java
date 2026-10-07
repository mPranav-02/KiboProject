package com.kibo.reservation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled trigger for expiry (FR-018): every {@code kibo.expiration.interval} (default 2 s) it
 * expires whatever is overdue, so units are back within the interval plus processing time, well inside the
 * 10 s bound. Expiry depends on this job and MySQL only, never on Redis or RabbitMQ (FR-027).
 *
 * <p>Every instance runs it. That is safe by design: the guarded updates in {@link HoldExpirationService}
 * make overlapping sweeps harmless, so no ShedLock, leader election or JVM lock is needed.
 * {@code fixedDelay} means a slow sweep never overlaps with the next one on the same instance.
 * The first run is one interval after startup; holds that were overdue at a restart are picked up then.
 */
@Component
public class HoldExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirationJob.class);

    private final HoldExpirationService expirationService;
    private final Clock clock;

    public HoldExpirationJob(HoldExpirationService expirationService, Clock clock) {
        this.expirationService = expirationService;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${kibo.expiration.interval}", fixedDelayString = "${kibo.expiration.interval}")
    public void run() {
        Instant started = clock.instant();
        try {
            HoldExpirationService.Summary summary = expirationService.expireOverdue(started);
            Duration took = Duration.between(started, clock.instant());
            if (summary.found() > 0) {
                log.atInfo()
                        .addKeyValue("found", summary.found())
                        .addKeyValue("expired", summary.expired())
                        .addKeyValue("failed", summary.failed())
                        .addKeyValue("tookMillis", took.toMillis())
                        .log("Expiration sweep finished");
            } else {
                log.debug("Expiration sweep finished, nothing overdue");
            }
        } catch (RuntimeException e) {
            // e.g. the database is unreachable. Never let it stop the schedule: the next sweep retries.
            log.error("Expiration sweep failed; will retry at the next interval", e);
        }
    }
}
