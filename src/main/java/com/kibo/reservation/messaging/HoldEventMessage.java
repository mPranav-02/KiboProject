package com.kibo.reservation.messaging;

import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The JSON body published for every lifecycle event: the wire contract in
 * specs/001-drop-reservation-service/contracts/events.md. It is deliberately its own type and not the
 * in-process {@link HoldLifecycleEvent}, so the published schema can only change on purpose.
 *
 * @param eventType {@code HOLD_CREATED}, {@code HOLD_CONFIRMED}, {@code HOLD_CANCELLED} or {@code HOLD_EXPIRED}
 * @param status    the hold's status AFTER the change
 */
public record HoldEventMessage(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID holdId,
        long dropId,
        String customerId,
        int quantity,
        String status,
        Instant expiresAt) {

    public static HoldEventMessage from(HoldLifecycleEvent event) {
        return new HoldEventMessage(event.eventId(), event.type().name(), event.occurredAt(), event.holdId(),
                event.dropId(), event.customerId(), event.quantity(), event.status().name(), event.expiresAt());
    }

    /** {@code hold.created|confirmed|cancelled|expired} (the topic the exchange routes on). */
    public static String routingKey(HoldLifecycleEvent.Type type) {
        return switch (type) {
            case HOLD_CREATED -> "hold.created";
            case HOLD_CONFIRMED -> "hold.confirmed";
            case HOLD_CANCELLED -> "hold.cancelled";
            case HOLD_EXPIRED -> "hold.expired";
        };
    }
}
