package com.kibo.reservation.domain.event;

import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Raised inside the transaction that actually changed a hold (created, confirmed, cancelled or expired),
 * and ONLY on that path: replays, repeats and rejected requests raise nothing (contracts/events.md).
 *
 * <p>It is an in-process Spring event. Listeners use {@code @TransactionalEventListener(AFTER_COMMIT)}, so
 * they never run for work that was rolled back. Today's listener evicts the drop cache; the RabbitMQ
 * publisher (messaging phase) will listen to the same event.
 *
 * @param status the hold's status AFTER the change
 */
public record HoldLifecycleEvent(
        UUID eventId,
        Type type,
        Instant occurredAt,
        UUID holdId,
        long dropId,
        String customerId,
        int quantity,
        HoldStatus status,
        Instant expiresAt) {

    public enum Type {
        HOLD_CREATED(true),
        HOLD_CONFIRMED(false),
        HOLD_CANCELLED(true),
        HOLD_EXPIRED(true);

        private final boolean changesAvailability;

        Type(boolean changesAvailability) {
            this.changesAvailability = changesAvailability;
        }

        /** Creating takes units; cancelling and expiring return them. Confirming leaves availability as it is. */
        public boolean changesAvailability() {
            return changesAvailability;
        }
    }

    public static HoldLifecycleEvent of(Type type, Hold hold, HoldStatus statusAfter, Instant occurredAt) {
        return new HoldLifecycleEvent(UUID.randomUUID(), type, occurredAt, hold.getId(), hold.getDropId(),
                hold.getCustomerId(), hold.getQuantity(), statusAfter, hold.getExpiresAt());
    }
}
