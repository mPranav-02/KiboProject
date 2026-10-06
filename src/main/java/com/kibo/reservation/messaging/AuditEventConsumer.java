package com.kibo.reservation.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * A deliberately tiny demo consumer: it reads the durable audit queue (bound with {@code hold.#}) and logs
 * each event. It exists only to show the topology working end to end. It changes no state, nothing depends
 * on it, and it can be switched off with {@code kibo.messaging.audit-consumer-enabled=false}.
 *
 * <p>Events are not the source of truth (MySQL is) and may be lost or, for a future outbox, repeated, so a
 * real consumer would key on {@code eventId} and use {@code status} / {@code occurredAt}.
 */
@Component
@ConditionalOnExpression("${kibo.messaging.enabled:true} and ${kibo.messaging.audit-consumer-enabled:true}")
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final ObjectMapper json;

    public AuditEventConsumer(ObjectMapper json) {
        this.json = json;
    }

    @RabbitListener(queues = "${kibo.messaging.audit-queue}")
    public void onMessage(Message message) {
        HoldEventMessage event;
        try {
            event = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8), HoldEventMessage.class);
        } catch (IOException e) {
            // A malformed message can never become valid: drop it instead of redelivering it forever.
            throw new AmqpRejectAndDontRequeueException("unreadable hold event", e);
        }
        log.info("AUDIT {} eventId={} holdId={} dropId={} customer={} quantity={} status={} occurredAt={}",
                event.eventType(), event.eventId(), event.holdId(), event.dropId(), event.customerId(),
                event.quantity(), event.status(), event.occurredAt());
    }
}
