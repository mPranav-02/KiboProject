package com.kibo.reservation.application;

import com.kibo.reservation.domain.Hold;

/**
 * Outcome of placing a hold.
 *
 * @param created true when this call created the hold; false when it replayed an earlier hold placed with
 *                the same customer and request key (no additional units consumed)
 */
public record HoldPlacement(Hold hold, boolean created) {
}
