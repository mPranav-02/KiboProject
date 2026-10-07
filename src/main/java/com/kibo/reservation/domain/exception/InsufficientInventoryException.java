package com.kibo.reservation.domain.exception;

import java.util.Map;

/**
 * Not enough units for an all-or-nothing hold (FR-006). {@code availableQuantity} is informational
 * (it may already have changed) and is returned to help the client decide what to do next.
 */
public class InsufficientInventoryException extends DomainException {

    private final int availableQuantity;

    public InsufficientInventoryException(long dropId, int requested, int availableQuantity) {
        super(ErrorCode.INSUFFICIENT_INVENTORY,
                "Requested " + requested + " units of drop " + dropId + " but only " + availableQuantity
                        + " are available");
        this.availableQuantity = availableQuantity;
    }

    public int availableQuantity() {
        return availableQuantity;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("availableQuantity", availableQuantity);
    }
}
