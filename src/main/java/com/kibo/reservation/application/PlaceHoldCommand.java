package com.kibo.reservation.application;

/** Input for placing a hold. {@code requestKey} is the client's idempotency key (FR-009). */
public record PlaceHoldCommand(long dropId, String customerId, String requestKey, int quantity) {
}
