package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.kibo.reservation.application.HoldExpirationService;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.config.MessagingProperties;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import com.kibo.reservation.domain.exception.HoldNotFoundException;
import com.kibo.reservation.domain.exception.InsufficientInventoryException;
import com.kibo.reservation.domain.exception.InvalidStateTransitionException;
import com.kibo.reservation.repository.DropRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lifecycle events against a REAL RabbitMQ: each real transition is published once, after commit, with the
 * contract's schema; replays, rejections and rolled-back work publish nothing; the topology behaves as a
 * topic exchange with a durable audit queue; and the names come from configuration.
 */
// Non-default names prove the exchange and queue really come from kibo.messaging.* (i.e. from the environment).
@TestPropertySource(properties = {"kibo.messaging.exchange=it.holds", "kibo.messaging.audit-queue=it.holds.audit"})
class RabbitEventsIT extends AbstractRabbitIT {

    private static final String QUEUE = "it.holds.audit";
    private static final String EXCHANGE = "it.holds";
    private static final Duration QUIET_PERIOD = Duration.ofMillis(1500);

    @Autowired
    private HoldService holds;

    @Autowired
    private HoldExpirationService expiration;

    @Autowired
    private DropRepository drops;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private MessagingProperties properties;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private ApplicationEventPublisher events;

    private long dropId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
        Instant now = clock.instant();
        dropId = drops.save(Drop.create("Open", null, 7, 4, now.minusSeconds(60), now)).getId();
        awaitBrokerAndPurge(QUEUE);
    }

    @Test
    void exchangeAndQueueNamesComeFromConfiguration() {
        assertThat(properties.exchange()).isEqualTo(EXCHANGE);
        assertThat(properties.auditQueue()).isEqualTo(QUEUE);
        assertThat(admin.getQueueProperties(QUEUE)).isNotNull();
    }

    @Test
    void everyRealTransitionPublishesExactlyOneEventWithTheContractSchema() throws Exception {
        UUID confirmed = holds.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2)).hold().getId();
        holds.confirm(confirmed, "alice");

        UUID cancelled = holds.placeHold(new PlaceHoldCommand(dropId, "bob", "k2", 1)).hold().getId();
        holds.cancel(cancelled, "bob");

        UUID expired = holds.placeHold(new PlaceHoldCommand(dropId, "carol", "k3", 3)).hold().getId();
        jdbc.update("UPDATE holds SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", expired.toString());
        expiration.expireOverdue(clock.instant());

        List<Message> received = receive(QUEUE, 6, Duration.ofSeconds(20));

        assertThat(received).as("created x3, confirmed, cancelled, expired").hasSize(6);
        Map<String, JsonNode> byTypeAndHold = new HashMap<>();
        for (Message message : received) {
            JsonNode event = body(message);
            byTypeAndHold.put(event.get("eventType").asText() + ":" + event.get("holdId").asText(), event);

            // transport: topic routing key, persistent JSON, id usable for de-duplication
            assertThat(message.getMessageProperties().getReceivedExchange()).isEqualTo(EXCHANGE);
            assertThat(message.getMessageProperties().getReceivedRoutingKey())
                    .isEqualTo(event.get("eventType").asText().toLowerCase().replace('_', '.'));
            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.get("eventId").asText());

            // schema: exactly the contract's fields, nothing else
            List<String> fields = new java.util.ArrayList<>();
            event.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("eventId", "eventType", "occurredAt", "holdId", "dropId",
                    "customerId", "quantity", "status", "expiresAt");
            assertThat(UUID.fromString(event.get("eventId").asText())).isNotNull();
            assertThat(Instant.parse(event.get("occurredAt").asText())).isNotNull();
            assertThat(Instant.parse(event.get("expiresAt").asText())).isNotNull();
            assertThat(event.get("dropId").asLong()).isEqualTo(dropId);
        }
        assertThat(byTypeAndHold).containsKeys(
                "HOLD_CREATED:" + confirmed, "HOLD_CONFIRMED:" + confirmed,
                "HOLD_CREATED:" + cancelled, "HOLD_CANCELLED:" + cancelled,
                "HOLD_CREATED:" + expired, "HOLD_EXPIRED:" + expired);

        // status is the status AFTER the change; customer and quantity come from the hold
        assertThat(byTypeAndHold.get("HOLD_CREATED:" + confirmed).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(byTypeAndHold.get("HOLD_CONFIRMED:" + confirmed).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(byTypeAndHold.get("HOLD_CANCELLED:" + cancelled).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(byTypeAndHold.get("HOLD_EXPIRED:" + expired).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(byTypeAndHold.get("HOLD_CONFIRMED:" + confirmed).get("customerId").asText()).isEqualTo("alice");
        assertThat(byTypeAndHold.get("HOLD_EXPIRED:" + expired).get("quantity").asInt()).isEqualTo(3);

        assertThat(receive(QUEUE, 1, QUIET_PERIOD)).as("and nothing more").isEmpty();
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void replaysRepeatsAndRejectionsPublishNothing() throws Exception {
        UUID id = holds.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2)).hold().getId();
        holds.confirm(id, "alice");
        UUID cancelled = holds.placeHold(new PlaceHoldCommand(dropId, "bob", "k2", 1)).hold().getId();
        holds.cancel(cancelled, "bob");
        holds.placeHold(new PlaceHoldCommand(dropId, "carol", "k3", 4));   // 7 - 2 - 4 = 1 left
        assertThat(receive(QUEUE, 5, Duration.ofSeconds(20))).hasSize(5);

        holds.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2));   // idempotent replay of the same request
        holds.confirm(id, "alice");                                        // confirm on CONFIRMED: repeat
        holds.cancel(cancelled, "bob");                                    // cancel on CANCELLED: repeat
        assertThatThrownBy(() -> holds.placeHold(new PlaceHoldCommand(dropId, "dave", "k9", 2)))
                .isInstanceOf(InsufficientInventoryException.class);       // only 1 left
        assertThatThrownBy(() -> holds.cancel(id, "alice"))
                .isInstanceOf(InvalidStateTransitionException.class);      // cancel after confirm
        assertThatThrownBy(() -> holds.confirm(cancelled, "bob"))
                .isInstanceOf(InvalidStateTransitionException.class);      // confirm after cancel
        assertThatThrownBy(() -> holds.cancel(id, "mallory"))
                .isInstanceOf(HoldNotFoundException.class);                // someone else's hold
        assertThatThrownBy(() -> holds.cancel(UUID.randomUUID(), "alice"))
                .isInstanceOf(HoldNotFoundException.class);                // unknown hold

        assertThat(receive(QUEUE, 1, QUIET_PERIOD)).as("no event for any of those").isEmpty();
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void publishesOnlyAfterCommitNeverForARolledBackTransaction() throws Exception {
        Hold hold = Hold.createActive(dropId, "alice", "k-tx", 1, clock.instant(), Duration.ofMinutes(5));
        TransactionTemplate tx = new TransactionTemplate(txManager);

        tx.executeWithoutResult(status -> {
            events.publishEvent(HoldLifecycleEvent.of(HoldLifecycleEvent.Type.HOLD_CREATED, hold, HoldStatus.ACTIVE, clock.instant()));
            status.setRollbackOnly();
        });
        assertThat(receive(QUEUE, 1, QUIET_PERIOD)).as("rolled back: nothing published").isEmpty();

        // Published inside the transaction, but not before it has committed.
        tx.executeWithoutResult(status -> {
            events.publishEvent(HoldLifecycleEvent.of(HoldLifecycleEvent.Type.HOLD_CREATED, hold, HoldStatus.ACTIVE, clock.instant()));
            assertThat(rabbit.receive(QUEUE, 500)).as("not yet: the transaction is still open").isNull();
        });
        List<Message> received = receive(QUEUE, 1, Duration.ofSeconds(10));
        assertThat(received).as("committed: published").hasSize(1);
        assertThat(body(received.get(0)).get("holdId").asText()).isEqualTo(hold.getId().toString());
    }

    @Test
    void theAuditQueueIsBoundWithAHoldWildcardOnATopicExchange() {
        Message message = MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8)).build();

        rabbit.send(EXCHANGE, "hold.something.else", message);   // matches hold.#
        rabbit.send(EXCHANGE, "other.created", message);          // does not: dropped by the broker

        List<Message> received = receive(QUEUE, 2, Duration.ofSeconds(3));
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getMessageProperties().getReceivedRoutingKey()).isEqualTo("hold.something.else");
    }
}
