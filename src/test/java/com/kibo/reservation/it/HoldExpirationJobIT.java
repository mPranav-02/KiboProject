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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The scheduled mechanism itself (FR-018): nothing in this test calls the expiry code. The application's own
 * {@code @Scheduled} job (interval shortened to 200 ms here) must find the overdue hold and expire it, with
 * the units back, well within the 10 s bound.
 *
 * <p>The context is discarded afterwards so that this fast-sweeping job cannot touch other tests' data.
 */
@TestPropertySource(properties = "kibo.expiration.interval=PT0.2S")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HoldExpirationJobIT extends AbstractMySqlIT {

    private static final int TOTAL = 10;

    @Autowired
    private HoldService holdService;

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
        dropId = drops.save(Drop.create("Drop", null, TOTAL, 4, now.minusSeconds(60), now)).getId();
    }

    @Test
    void theScheduledJobExpiresAnOverdueHoldAndReturnsItsUnitsWithoutAnyoneCallingIt() throws Exception {
        UUID overdue = place("a", 3);
        UUID notDue = place("b", 2);
        UUID confirmed = place("c", 1);
        holdService.confirm(confirmed, "c");
        jdbc.update("UPDATE holds SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id IN (?, ?)",
                overdue.toString(), confirmed.toString());
        Instant start = clock.instant();

        boolean expired = awaitTrue(() -> "EXPIRED".equals(status(overdue)), Duration.ofSeconds(10));

        Duration took = Duration.between(start, clock.instant());
        assertThat(expired).as("expired by the scheduler within the 10 s bound").isTrue();
        assertThat(took).isLessThan(Duration.ofSeconds(10));
        assertThat(status(notDue)).isEqualTo("ACTIVE");
        assertThat(status(confirmed)).as("a confirmed hold is never expired").isEqualTo("CONFIRMED");
        assertThat(available()).as("only the expired hold's 3 units came back").isEqualTo(TOTAL - 2 - 1);

        // Many more scheduler ticks must not return the units again.
        Thread.sleep(1_000);
        assertThat(available()).isEqualTo(TOTAL - 2 - 1);
        assertInventoryInvariant(jdbc, dropId);
    }

    private UUID place(String customer, int quantity) {
        return holdService.placeHold(new PlaceHoldCommand(dropId, customer, "key-" + customer, quantity)).hold().getId();
    }

    private static boolean awaitTrue(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, id.toString());
    }

    private int available() {
        return jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId);
    }
}
