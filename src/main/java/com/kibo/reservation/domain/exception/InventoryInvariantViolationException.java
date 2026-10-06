package com.kibo.reservation.domain.exception;

/**
 * An inventory invariant would be broken, for example units could not be returned for a hold that was just
 * cancelled. This is a bug or data corruption, never a client error, so it is intentionally NOT a
 * {@link DomainException}: it is thrown inside the transaction (rolling back the state change with it) and
 * answered as a generic 500 with the details only in the log.
 */
public class InventoryInvariantViolationException extends RuntimeException {

    public InventoryInvariantViolationException(String message) {
        super(message);
    }
}
