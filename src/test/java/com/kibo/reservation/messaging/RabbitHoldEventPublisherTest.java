package com.kibo.reservation.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kibo.reservation.config.MessagingProperties;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import com.kibo.reservation.domain.event.HoldLifecycleEvent.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Async;

class RabbitHoldEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final RabbitHoldEventPublisher publisher = new RabbitHoldEventPublisher(
            rabbit, json, new MessagingProperties(true, "test.exchange", "test.queue", false));

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger publisherLogger;

    @BeforeEach
    void captureLogs() {
        publisherLogger = (Logger) LoggerFactory.getLogger(RabbitHoldEventPublisher.class);
        logs.start();
        publisherLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        publisherLogger.detachAppender(logs);
    }

    @Test
    void publishesToTheConfiguredExchangeWithTheRoutingKeyAndAPersistentJsonBody() throws Exception {
        brokerConfirms(true);
        HoldLifecycleEvent event = event(Type.HOLD_CANCELLED, HoldStatus.CANCELLED);

        publisher.onHoldChanged(event);

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("test.exchange"), eq("hold.cancelled"), message.capture(), any(CorrelationData.class));

        MessageProperties props = message.getValue().getMessageProperties();
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat(props.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(props.getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(props.getType()).isEqualTo("HOLD_CANCELLED");

        JsonNode body = json.readTree(new String(message.getValue().getBody(), StandardCharsets.UTF_8));
        assertThat(body.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(body.get("eventType").asText()).isEqualTo("HOLD_CANCELLED");
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("holdId").asText()).isEqualTo(event.holdId().toString());
        assertThat(logs.list).noneMatch(l -> l.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @Test
    void usesTheRightRoutingKeyForEveryEventType() {
        brokerConfirms(true);

        for (Type type : Type.values()) {
            publisher.onHoldChanged(event(type, HoldStatus.ACTIVE));
        }

        for (String key : new String[] {"hold.created", "hold.confirmed", "hold.cancelled", "hold.expired"}) {
            verify(rabbit).send(eq("test.exchange"), eq(key), any(Message.class), any(CorrelationData.class));
        }
    }

    @Test
    void aBrokerExceptionIsSwallowedAndLoggedWithThePayload() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("Connection refused")))
                .when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
        HoldLifecycleEvent event = event(Type.HOLD_CREATED, HoldStatus.ACTIVE);

        assertThatCode(() -> publisher.onHoldChanged(event)).doesNotThrowAnyException();

        assertThat(logs.list).anySatisfy(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.WARN);
            assertThat(entry.getFormattedMessage())
                    .contains("NOT published")
                    .contains(event.eventId().toString())
                    .contains("hold.created")
                    .contains("\"eventType\":\"HOLD_CREATED\"")
                    .contains("Connection refused");
        });
    }

    @Test
    void aBrokerNackIsSwallowedAndLogged() {
        brokerConfirms(false);

        assertThatCode(() -> publisher.onHoldChanged(event(Type.HOLD_EXPIRED, HoldStatus.EXPIRED)))
                .doesNotThrowAnyException();

        assertThat(logs.list).anySatisfy(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.WARN);
            assertThat(entry.getFormattedMessage()).contains("NOT published").contains("broker nack");
        });
    }

    @Test
    void aMissingConfirmIsSwallowedAndLoggedAfterTheTimeout() {
        // Broker accepted the connection but never confirms: complete the future with a timeout-ish failure.
        doAnswer(invocation -> {
            ((CorrelationData) invocation.getArgument(3)).getFuture()
                    .completeExceptionally(new java.util.concurrent.TimeoutException("no confirm"));
            return null;
        }).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        assertThatCode(() -> publisher.onHoldChanged(event(Type.HOLD_CONFIRMED, HoldStatus.CONFIRMED)))
                .doesNotThrowAnyException();

        assertThat(logs.list).anySatisfy(entry -> assertThat(entry.getFormattedMessage()).contains("NOT published"));
    }

    @Test
    void runsOnlyAfterCommitAndOffTheRequestThread() throws Exception {
        var method = RabbitHoldEventPublisher.class.getMethod("onHoldChanged", HoldLifecycleEvent.class);

        TransactionalEventListener listener = method.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).as("never for work outside a committed transaction").isFalse();

        Async async = method.getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("eventPublisherExecutor");
    }

    /** Completes the confirm future the publisher waits on, as the broker would. */
    private void brokerConfirms(boolean ack) {
        doAnswer(invocation -> {
            ((CorrelationData) invocation.getArgument(3)).getFuture()
                    .complete(new CorrelationData.Confirm(ack, ack ? null : "queue limit"));
            return null;
        }).when(rabbit).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    private static HoldLifecycleEvent event(Type type, HoldStatus statusAfter) {
        Hold hold = Hold.createActive(7L, "alice", "k", 2, NOW, Duration.ofMinutes(5));
        return HoldLifecycleEvent.of(type, hold, statusAfter, NOW);
    }
}
