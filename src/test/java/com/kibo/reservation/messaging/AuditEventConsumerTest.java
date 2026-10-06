package com.kibo.reservation.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

class AuditEventConsumerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AuditEventConsumer consumer = new AuditEventConsumer(json);

    @Test
    void logsAPublishedEvent() throws Exception {
        Hold hold = Hold.createActive(3L, "carol", "k", 1, NOW, Duration.ofMinutes(5));
        HoldEventMessage event = HoldEventMessage.from(
                HoldLifecycleEvent.of(HoldLifecycleEvent.Type.HOLD_CONFIRMED, hold, HoldStatus.CONFIRMED, NOW));
        Message message = MessageBuilder.withBody(json.writeValueAsBytes(event)).build();

        Logger logger = (Logger) LoggerFactory.getLogger(AuditEventConsumer.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatCode(() -> consumer.onMessage(message)).doesNotThrowAnyException();
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).singleElement().satisfies(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.INFO);
            assertThat(entry.getFormattedMessage())
                    .contains("AUDIT HOLD_CONFIRMED")
                    .contains(event.eventId().toString())
                    .contains("status=CONFIRMED");
        });
    }

    @Test
    void rejectsAMalformedMessageWithoutRequeueing() {
        Message garbage = MessageBuilder.withBody("not json".getBytes(StandardCharsets.UTF_8)).build();

        assertThatThrownBy(() -> consumer.onMessage(garbage)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}
