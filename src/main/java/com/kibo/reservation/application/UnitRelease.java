package com.kibo.reservation.application;

import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.exception.InventoryInvariantViolationException;
import com.kibo.reservation.repository.DropRepository;
import java.time.Instant;

/**
 * The one place that returns a resolved hold's units to its drop (cancel and expire share it).
 *
 * <p>Must be called only from the transaction whose guarded hold transition affected exactly one row;
 * that is what makes the return happen exactly once. The drop-side update refuses to push availability
 * above the fixed total, so a double return can never go unnoticed.
 */
final class UnitRelease {

    private UnitRelease() {
    }

    /**
     * @throws InventoryInvariantViolationException if the units cannot be returned; the caller's
     *         transaction rolls back, undoing the hold's status change with it
     */
    static void returnUnits(DropRepository drops, Hold hold, Instant now) {
        if (drops.releaseUnits(hold.getDropId(), hold.getQuantity(), now) != 1) {
            throw new InventoryInvariantViolationException("Could not return " + hold.getQuantity()
                    + " units of drop " + hold.getDropId() + " for hold " + hold.getId());
        }
    }
}
