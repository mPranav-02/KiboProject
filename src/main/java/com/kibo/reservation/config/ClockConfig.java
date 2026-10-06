package com.kibo.reservation.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single time source (research.md §13). Injected everywhere and passed into queries as {@code :now},
 * so tests can control time.
 *
 * <p>Ticks in whole microseconds to match MySQL {@code DATETIME(6)}: the in-memory {@code expiresAt}
 * of a new hold is then exactly what is stored, so the expiry boundary is the same in Java and SQL.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
