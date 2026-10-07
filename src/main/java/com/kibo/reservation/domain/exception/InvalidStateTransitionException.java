package com.kibo.reservation.domain.exception;

import com.kibo.reservation.domain.HoldStatus;
import java.util.Map;
import java.util.UUID;

/**
 * The requested transition is not allowed from the hold's current state, e.g. cancelling a CONFIRMED hold
 * or confirming a CANCELLED one (FR-013). Reports the current state; nothing was changed.
 */
public class InvalidStateTransitionException extends DomainException {

    private final HoldStatus currentStatus;

    public InvalidStateTransitionException(UUID holdId, HoldStatus currentStatus, HoldStatus requested) {
        super(ErrorCode.INVALID_STATE_TRANSITION,
                "Hold " + holdId + " is " + currentStatus + " and cannot become " + requested);
        this.currentStatus = currentStatus;
    }

    public HoldStatus currentStatus() {
        return currentStatus;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("currentStatus", currentStatus.name());
    }
}
