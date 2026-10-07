package com.kibo.reservation.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Availability status shown to customers (spec FR-001). Derived, never stored, and always computed
 * with the current clock so that a cached drop snapshot still flips from UPCOMING to OPEN on time.
 */
public enum DropAvailabilityStatus {
    UPCOMING,
    OPEN,
    SOLD_OUT;

    /**
     * UPCOMING before the release time; otherwise SOLD_OUT when no units remain; otherwise OPEN.
     * A drop is released at exactly {@code startsAt} (holds are allowed when {@code startsAt <= now}).
     */
    public static DropAvailabilityStatus of(Instant startsAt, int availableQuantity, Instant now) {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(now, "now");
        if (now.isBefore(startsAt)) {
            return UPCOMING;
        }
        return availableQuantity <= 0 ? SOLD_OUT : OPEN;
    }
}
