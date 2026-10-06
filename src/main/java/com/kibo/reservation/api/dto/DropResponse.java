package com.kibo.reservation.api.dto;

import com.kibo.reservation.application.DropSnapshot;
import com.kibo.reservation.domain.DropAvailabilityStatus;
import java.time.Instant;

/** API representation of a drop (contracts/openapi.yaml {@code DropResponse}). */
public record DropResponse(
        long id,
        String name,
        String description,
        int totalQuantity,
        int availableQuantity,
        int maxPerHold,
        Instant startsAt,
        DropAvailabilityStatus availabilityStatus) {

    /** The status is derived with the current time, never taken from stored or cached data. */
    public static DropResponse from(DropSnapshot drop, Instant now) {
        return new DropResponse(
                drop.id(),
                drop.name(),
                drop.description(),
                drop.totalQuantity(),
                drop.availableQuantity(),
                drop.maxPerHold(),
                drop.startsAt(),
                DropAvailabilityStatus.of(drop.startsAt(), drop.availableQuantity(), now));
    }
}
