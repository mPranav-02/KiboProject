package com.kibo.reservation.domain.exception;

import java.time.Instant;
import java.util.Map;

/** Holds are rejected before the drop's release time (FR-007). */
public class DropNotReleasedException extends DomainException {

    private final Instant startsAt;

    public DropNotReleasedException(long dropId, Instant startsAt) {
        super(ErrorCode.DROP_NOT_RELEASED, "Drop " + dropId + " is not released until " + startsAt);
        this.startsAt = startsAt;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("startsAt", startsAt);
    }
}
