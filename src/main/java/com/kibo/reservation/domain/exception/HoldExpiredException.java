package com.kibo.reservation.domain.exception;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Confirm or cancel was rejected because the hold has expired (FR-015, FR-017), including an ACTIVE hold
 * that is past its expiry time but not yet processed by the expiry job.
 */
public class HoldExpiredException extends DomainException {

    private final Instant expiresAt;

    public HoldExpiredException(UUID holdId, Instant expiresAt) {
        super(ErrorCode.HOLD_EXPIRED, "Hold " + holdId + " expired at " + expiresAt);
        this.expiresAt = expiresAt;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("currentStatus", "EXPIRED", "expiresAt", expiresAt);
    }
}
