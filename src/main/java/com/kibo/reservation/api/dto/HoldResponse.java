package com.kibo.reservation.api.dto;

import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import java.time.Instant;
import java.util.UUID;

/** API representation of a hold (contracts/openapi.yaml {@code HoldResponse}). */
public record HoldResponse(
        UUID id,
        long dropId,
        int quantity,
        HoldStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant resolvedAt) {

    /** Uses the effective status: an ACTIVE hold past its expiry is shown as EXPIRED (FR-019). */
    public static HoldResponse from(Hold hold, Instant now) {
        return new HoldResponse(hold.getId(), hold.getDropId(), hold.getQuantity(), hold.effectiveStatus(now),
                hold.getCreatedAt(), hold.getExpiresAt(), hold.getResolvedAt());
    }
}
