package com.kibo.reservation.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed business settings under {@code kibo.*} (defaults in application.yml). Validated at startup so
 * a bad value fails fast. Infrastructure settings are NOT here: they come from the environment.
 */
@Validated
@ConfigurationProperties(prefix = "kibo")
public record KiboProperties(
        @Valid @NotNull HoldSettings hold,
        @Valid @NotNull ExpirationSettings expiration,
        @Valid @NotNull CacheSettings cache,
        @Valid @NotNull SeedSettings seed) {

    /** How long a hold lasts (default PT5M) and the per-hold unit maximum for new drops (default 4). */
    public record HoldSettings(@NotNull Duration duration, @Min(1) int defaultMaxPerHold) {
    }

    /** Expiry sweep interval (default PT2S, must keep release within 10s, FR-018) and batch size. */
    public record ExpirationSettings(@NotNull Duration interval, @Min(1) int batchSize) {
    }

    /** Drop read-cache TTL (default PT3S): the upper bound on how stale a cached drop can be (FR-028, SC-008). */
    public record CacheSettings(@NotNull Duration dropTtl) {
    }

    public record SeedSettings(boolean enabled) {
    }
}
