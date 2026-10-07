package com.kibo.reservation.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import com.kibo.reservation.domain.event.HoldLifecycleEvent.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The published JSON is the contract in contracts/events.md: field names, formats and routing keys. */
class HoldEventMessageTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00.123456Z");

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @ParameterizedTest
    @CsvSource({
        "HOLD_CREATED,   hold.created",
        "HOLD_CONFIRMED, hold.confirmed",
        "HOLD_CANCELLED, hold.cancelled",
        "HOLD_EXPIRED,   hold.expired"
    })
    void everyEventTypeHasItsRoutingKey(Type type, String routingKey) {
        assertThat(HoldEventMessage.routingKey(type)).isEqualTo(routingKey);
    }

    @Test
    void everyEventTypeIsCoveredByTheAuditBindingPattern() {
        // The audit queue is bound with "hold.#": each routing key must start with "hold.".
        for (Type type : Type.values()) {
            assertThat(HoldEventMessage.routingKey(type)).startsWith("hold.");
        }
    }

    @Test
    void serialisesExactlyTheContractFields() throws Exception {
        Hold hold = Hold.createActive(1L, "cust-42", "key", 2, NOW, Duration.ofMinutes(5));
        HoldLifecycleEvent event = HoldLifecycleEvent.of(Type.HOLD_CREATED, hold, HoldStatus.ACTIVE, NOW);

        JsonNode node = json.readTree(json.writeValueAsString(HoldEventMessage.from(event)));

        assertThat(fieldNames(node)).containsExactlyInAnyOrder("eventId", "eventType", "occurredAt", "holdId",
                "dropId", "customerId", "quantity", "status", "expiresAt");
        assertThat(node.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(node.get("eventType").asText()).isEqualTo("HOLD_CREATED");
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-10-06T10:00:00.123456Z");
        assertThat(node.get("holdId").asText()).isEqualTo(hold.getId().toString());
        assertThat(node.get("dropId").asLong()).isEqualTo(1L);
        assertThat(node.get("customerId").asText()).isEqualTo("cust-42");
        assertThat(node.get("quantity").asInt()).isEqualTo(2);
        assertThat(node.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(node.get("expiresAt").asText()).isEqualTo("2026-10-06T10:05:00.123456Z");
    }

    @Test
    void statusIsTheStatusAfterTheChange() {
        Hold hold = Hold.createActive(1L, "cust-42", "key", 2, NOW, Duration.ofMinutes(5));

        assertThat(HoldEventMessage.from(HoldLifecycleEvent.of(Type.HOLD_CONFIRMED, hold, HoldStatus.CONFIRMED, NOW))
                .status()).isEqualTo("CONFIRMED");
        assertThat(HoldEventMessage.from(HoldLifecycleEvent.of(Type.HOLD_CANCELLED, hold, HoldStatus.CANCELLED, NOW))
                .status()).isEqualTo("CANCELLED");
        assertThat(HoldEventMessage.from(HoldLifecycleEvent.of(Type.HOLD_EXPIRED, hold, HoldStatus.EXPIRED, NOW))
                .status()).isEqualTo("EXPIRED");
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        Hold hold = Hold.createActive(9L, "bob", "k", 1, NOW, Duration.ofMinutes(5));
        HoldEventMessage message = HoldEventMessage.from(HoldLifecycleEvent.of(Type.HOLD_EXPIRED, hold, HoldStatus.EXPIRED, NOW));

        HoldEventMessage back = json.readValue(json.writeValueAsString(message), HoldEventMessage.class);

        assertThat(back).isEqualTo(message);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
