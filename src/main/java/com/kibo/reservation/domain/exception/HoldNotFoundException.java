package com.kibo.reservation.domain.exception;

/**
 * No hold with this id exists for this customer. A hold that belongs to another customer, an unknown id
 * and a malformed id are deliberately indistinguishable (FR-022a): no existence oracle.
 */
public class HoldNotFoundException extends DomainException {

    public HoldNotFoundException(String holdId) {
        super(ErrorCode.HOLD_NOT_FOUND, "Hold " + holdId + " was not found");
    }
}
