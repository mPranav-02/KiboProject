package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;

import com.kibo.reservation.application.HoldPlacement;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.exception.InsufficientInventoryException;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SC-001: inventory can never be oversold, no matter how many requests race for it.
 *
 * <p>All requests are released at the same instant by a start gate, and each one runs in its own thread,
 * its own transaction and its own pooled connection, exactly like concurrent HTTP requests. There is no
 * JVM-level coordination in the code under test, so any oversell would show up here.
 * Repeat count: {@code -Dkibo.it.runs=N} (default 10; use 100 for the full SC-001 run).
 */
class ConcurrentReservationIT extends AbstractMySqlIT {

    private static final int RUNS = Integer.getInteger("kibo.it.runs", 10);

    @Autowired
    private HoldService holdService;

    @Autowired
    private DropRepository drops;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
    }

    @Test
    void twoHundredConcurrentRequestsForFiftyUnitsGrantExactlyFifty() throws Exception {
        for (int run = 1; run <= RUNS; run++) {
            long dropId = newDrop(50, 4);

            Outcome outcome = race(dropId, 200, i -> 1, "run" + run);

            assertThat(outcome.unexpected).as("run %d unexpected errors", run).isEmpty();
            assertThat(outcome.granted).as("run %d holds granted", run).hasSize(50);
            assertThat(outcome.rejected).as("run %d rejections", run).isEqualTo(150);
            assertThat(available(dropId)).as("run %d available after", run).isZero();
            assertThat(holdCount(dropId)).as("run %d hold rows", run).isEqualTo(50);
            assertInventoryInvariant(jdbc, dropId);
        }
    }

    @Test
    void lastUnitGoesToExactlyOneOfManyConcurrentRequests() throws Exception {
        long dropId = newDrop(1, 1);

        Outcome outcome = race(dropId, 100, i -> 1, "last");

        assertThat(outcome.unexpected).isEmpty();
        assertThat(outcome.granted).hasSize(1);
        assertThat(outcome.rejected).isEqualTo(99);
        assertThat(available(dropId)).isZero();
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void mixedQuantitiesAreAllOrNothingAndNeverExceedStock() throws Exception {
        for (int run = 1; run <= RUNS; run++) {
            long dropId = newDrop(5, 3);

            Outcome outcome = race(dropId, 60, i -> 1 + (i % 3), "mixed" + run); // asks for 1, 2 and 3 units

            assertThat(outcome.unexpected).isEmpty();
            int unitsGranted = outcome.granted.stream().mapToInt(p -> p.hold().getQuantity()).sum();
            assertThat(unitsGranted).as("run %d units granted", run).isLessThanOrEqualTo(5);
            assertThat(available(dropId)).isEqualTo(5 - unitsGranted);
            // Stock can always be fully used by 1-unit requests, so it must end at zero.
            assertThat(available(dropId)).as("run %d leftover", run).isZero();
            // Every granted hold has exactly the quantity that was requested (no partial grants).
            assertThat(outcome.granted).allSatisfy(p ->
                    assertThat(p.hold().getQuantity()).isEqualTo(1 + Integer.parseInt(p.hold().getRequestKey().split("-")[1]) % 3));
            assertInventoryInvariant(jdbc, dropId);
        }
    }

    private Outcome race(long dropId, int requests, IntUnaryOperator quantityOf, String keyPrefix) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return holdService.placeHold(new PlaceHoldCommand(
                                dropId, "customer-" + n, keyPrefix + "-" + n, quantityOf.applyAsInt(n)));
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown(); // start gate: every request fires at once

            Outcome outcome = new Outcome();
            for (Future<Object> f : futures) {
                Object result = f.get(60, TimeUnit.SECONDS);
                if (result instanceof HoldPlacement placement && placement.created()) {
                    outcome.granted.add(placement);
                } else if (result instanceof InsufficientInventoryException) {
                    outcome.rejected++;
                } else {
                    outcome.unexpected.add(result);
                }
            }
            return outcome;
        } finally {
            pool.shutdownNow();
        }
    }

    private long newDrop(int total, int maxPerHold) {
        Instant now = clock.instant();
        return drops.save(Drop.create("Race", null, total, maxPerHold, now.minusSeconds(60), now)).getId();
    }

    private int available(long dropId) {
        return jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId);
    }

    private int holdCount(long dropId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM holds WHERE drop_id = ?", Integer.class, dropId);
    }

    private static final class Outcome {
        final List<HoldPlacement> granted = new ArrayList<>();
        int rejected;
        final List<Object> unexpected = new ArrayList<>();
    }
}
