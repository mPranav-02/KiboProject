package com.kibo.reservation.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kibo.reservation.config.MessagingProperties;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes hold lifecycle events to RabbitMQ. This is the ONLY class that talks to the broker; the
 * business code just raises an in-process {@link HoldLifecycleEvent} and knows nothing about messaging.
 *
 * <p>Delivery is AT-MOST-ONCE, by design (research.md section 8, docs/messaging-rabbitmq.md):
 * <ul>
 *   <li>{@code AFTER_COMMIT}: only for work that really committed, so a rolled-back hold never produces
 *       an event.</li>
 *   <li>{@code @Async} on a bounded executor: the request thread has already returned, and a slow or dead
 *       broker can cost only this pool.</li>
 *   <li>A failure (broker down, connection timeout, no confirm, nack) is logged at WARN with the payload
 *       and swallowed. There is no retry and no outbox, so such an event is lost; the state is still
 *       correct in MySQL.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "kibo.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitHoldEventPublisher {

    /** How long to wait for the broker to confirm one message before giving up on it. */
    static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private static final Logger log = LoggerFactory.getLogger(RabbitHoldEventPublisher.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final MessagingProperties properties;

    public RabbitHoldEventPublisher(RabbitTemplate rabbit, ObjectMapper json, MessagingProperties properties) {
        this.rabbit = rabbit;
        this.json = json;
        this.properties = properties;
    }

    @Async("eventPublisherExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHoldChanged(HoldLifecycleEvent event) {
        String routingKey = HoldEventMessage.routingKey(event.type());
        String payload = null;
        try {
            payload = json.writeValueAsString(HoldEventMessage.from(event));
            publish(event, routingKey, payload);
        } catch (Exception e) {
            // Never rethrown: a lost event must not affect anything that already committed (FR-027).
            log.warn("Lifecycle event NOT published (at-most-once, event is lost): eventId={} type={} "
                    + "routingKey={} payload={} cause={}", event.eventId(), event.type(), routingKey, payload, e.toString());
        }
    }

    private void publish(HoldLifecycleEvent event, String routingKey, String payload) throws Exception {
        Message message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.eventId().toString())
                .setType(event.type().name())
                .build();

        CorrelationData correlation = new CorrelationData(event.eventId().toString());
        rabbit.send(properties.exchange(), routingKey, message, correlation);

        // Publisher confirms: wait (on this pool thread, never a request thread) until the broker has taken
        // responsibility for the message, so a rejected or unconfirmed publish is reported, not silent.
        CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!confirm.isAck()) {
            throw new IllegalStateException("broker nack: " + confirm.getReason());
        }
        log.debug("Lifecycle event published: eventId={} type={} routingKey={}", event.eventId(), event.type(), routingKey);
    }
}
