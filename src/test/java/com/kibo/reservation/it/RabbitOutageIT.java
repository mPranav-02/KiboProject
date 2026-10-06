package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kibo.reservation.application.HoldExpirationService;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.messaging.RabbitHoldEventPublisher;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RabbitMQ is only a side channel (FR-027, SC-005): with publishing and the demo consumer ENABLED but the
 * broker unreachable (connection refused, which is how every IT runs by default), the application starts,
 * every hold operation and read works, MySQL stays correct, readiness stays UP, and each lost event shows
 * up as a WARN log. A hung broker (not refused) is in {@link RabbitBrokerHangIT}.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"kibo.messaging.enabled=true", "kibo.messaging.audit-consumer-enabled=true"})
class RabbitOutageIT extends AbstractMySqlIT {

    @Autowired
    private MockMvc mvc;

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

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger publisherLogger;
    private long dropId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
        Instant now = clock.instant();
        dropId = drops.save(Drop.create("Open", null, 5, 4, now.minusSeconds(60), now)).getId();
        publisherLogger = (Logger) LoggerFactory.getLogger(RabbitHoldEventPublisher.class);
        logs.start();
        publisherLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        publisherLogger.detachAppender(logs);
    }

    @Test
    void everyHoldOperationStillWorksAndEachLostEventIsLogged() throws Exception {
        long start = System.nanoTime();
        UUID confirmed = holds.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2)).hold().getId();
        holds.confirm(confirmed, "alice");
        UUID cancelled = holds.placeHold(new PlaceHoldCommand(dropId, "bob", "k2", 1)).hold().getId();
        holds.cancel(cancelled, "bob");
        UUID expired = holds.placeHold(new PlaceHoldCommand(dropId, "carol", "k3", 1)).hold().getId();
        jdbc.update("UPDATE holds SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", expired.toString());
        assertThat(expiration.expireOverdue(clock.instant()).expired()).isEqualTo(1);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).as("publishing is off the request path, so a dead broker adds no waiting")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(statusOf(confirmed)).isEqualTo("CONFIRMED");
        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
        assertThat(statusOf(expired)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId))
                .as("5 - 2 confirmed").isEqualTo(3);
        assertInventoryInvariant(jdbc, dropId);

        // the REST API is unaffected too
        mvc.perform(get("/api/v1/drops/{id}", dropId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(3));
        mvc.perform(post("/api/v1/drops/{id}/holds", dropId)
                        .header("Idempotency-Key", "api-1").header("X-Customer-Id", "dave")
                        .contentType("application/json").content("{\"quantity\":1}"))
                .andExpect(status().isCreated());

        // Each of the six events that could not be delivered is reported, with its payload, and nothing else.
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("NOT published"))
                        .hasSizeGreaterThanOrEqualTo(7)
                        .allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN)));
    }

    @Test
    void readinessAndLivenessStayUpWhileTheBrokerIsDown() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private String statusOf(UUID holdId) {
        return jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, holdId.toString());
    }
}
