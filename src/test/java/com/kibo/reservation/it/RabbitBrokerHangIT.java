package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;

import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The harshest broker failure: connections stay open but the broker stops answering (container paused).
 * Hold operations must neither fail nor slow down, because publishing waits on its own pool threads; and
 * once the broker is back, new events flow again by themselves.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RabbitBrokerHangIT extends AbstractRabbitIT {

    private static final String QUEUE = "kibo.holds.audit";

    @Autowired
    private HoldService holds;

    @Autowired
    private DropRepository drops;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long dropId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
        Instant now = clock.instant();
        dropId = drops.save(Drop.create("Open", null, 50, 4, now.minusSeconds(60), now)).getId();
        awaitBrokerAndPurge(QUEUE);
        // healthy first: warm up the connection and prove events flow before the failure
        UUID warmup = holds.placeHold(new PlaceHoldCommand(dropId, "warmup", "k0", 1)).hold().getId();
        assertThat(receive(QUEUE, 1, Duration.ofSeconds(20))).as("event for %s", warmup).hasSize(1);
    }

    @Test
    void aHungBrokerNeverFailsOrSlowsHoldOperationsAndEventsResumeAfterwards() throws Exception {
        RABBIT.getDockerClient().pauseContainerCmd(RABBIT.getContainerId()).exec();
        long slowest = 0;
        try {
            for (int i = 1; i <= 15; i++) {
                long t = System.nanoTime();
                UUID id = holds.placeHold(new PlaceHoldCommand(dropId, "u" + i, "k" + i, 1)).hold().getId();
                if (i % 3 == 0) {
                    holds.cancel(id, "u" + i);
                } else if (i % 3 == 1) {
                    holds.confirm(id, "u" + i);
                }
                slowest = Math.max(slowest, System.nanoTime() - t);
            }
            assertThat(Duration.ofNanos(slowest)).as("slowest hold operation while the broker hangs")
                    .isLessThan(Duration.ofSeconds(2));
            assertInventoryInvariant(jdbc, dropId);
        } finally {
            RABBIT.getDockerClient().unpauseContainerCmd(RABBIT.getContainerId()).exec();
        }

        // Events produced during the hang may or may not have arrived (at-most-once). What must hold is
        // that a NEW event is delivered once the broker answers again, with no restart and no manual step.
        UUID after = holds.placeHold(new PlaceHoldCommand(dropId, "after", "k-after", 1)).hold().getId();
        Awaitility.await("event published after the broker came back").atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                    boolean found = false;
                    for (var message : receive(QUEUE, 100, Duration.ofSeconds(1))) {
                        found |= body(message).get("holdId").asText().equals(after.toString());
                    }
                    assertThat(found).isTrue();
                });
        assertInventoryInvariant(jdbc, dropId);
    }
}
