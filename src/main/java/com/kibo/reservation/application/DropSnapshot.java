package com.kibo.reservation.application;

import com.kibo.reservation.domain.Drop;
import java.time.Instant;

/**
 * Immutable read model of a drop. Deliberately excludes the availability status, which depends on
 * the current time and is computed when the response is built, so this snapshot can later be cached
 * without the status going stale.
 */
public record DropSnapshot(
        long id,
        String name,
        String description,
        int totalQuantity,
        int availableQuantity,
        int maxPerHold,
        Instant startsAt) {

    public static DropSnapshot of(Drop drop) {
        return new DropSnapshot(
                drop.getId(),
                drop.getName(),
                drop.getDescription(),
                drop.getTotalQuantity(),
                drop.getAvailableQuantity(),
                drop.getMaxPerHold(),
                drop.getStartsAt());
    }
}
