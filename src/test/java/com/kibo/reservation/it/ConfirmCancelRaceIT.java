package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;

import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.exception.InvalidStateTransitionException;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Races on confirm and cancel (FR-020, FR-021, FR-025). Every request runs in its own thread, transaction and
 * pooled connection, released together by a start gate, with no JVM-level coordination in the code under
 * test: a double return of units or a hold with two outcomes would show up here as a broken invariant or an
 * unexpected result. Repeat count: {@code -Dkibo.it.runs=N} (default 10; use 100 for a heavier run).
 */
class ConfirmCancelRaceIT extends AbstractMySqlIT {

    private static final int RUNS = Integer.getInteger("kibo.it.runs", 10);
    private static final int TOTAL = 10;
    private static final int QUANTITY = 4;

    @Autowired
    private HoldService holdService;

    @Autowired
    private DropRepository drops;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
    }

    /** One confirm against one cancel of the same hold: exactly one wins, the other is rejected. */
    @Test
    void cancelVersusConfirmHasExactlyOneWinner() throws Exception {
        int confirmWins = 0;
        int cancelWins = 0;
        for (int run = 1; run <= RUNS; run++) {
            long dropId = newDrop();
            UUID id = place(dropId, "alice", "k" + run);

            List<Object> results = race(List.of(
                    () -> holdService.confirm(id, "alice"),
                    () -> holdService.cancel(id, "alice")));

            HoldStatus finalStatus = statusOf(id);
            assertThat(finalStatus).as("run %d final state", run).isIn(HoldStatus.CONFIRMED, HoldStatus.CANCELLED);
            Object confirmResult = results.get(0);
            Object cancelResult = results.get(1);
            if (finalStatus == HoldStatus.CONFIRMED) {
                confirmWins++;
                assertThat(confirmResult).as("run %d confirm", run).isInstanceOf(Hold.class);
                assertThat(cancelResult).as("run %d cancel", run).isInstanceOf(InvalidStateTransitionException.class);
                assertThat(available(dropId)).as("run %d confirmed units stay consumed", run).isEqualTo(TOTAL - QUANTITY);
            } else {
                cancelWins++;
                assertThat(cancelResult).as("run %d cancel", run).isInstanceOf(Hold.class);
                assertThat(confirmResult).as("run %d confirm", run).isInstanceOf(InvalidStateTransitionException.class);
                assertThat(available(dropId)).as("run %d cancelled units returned once", run).isEqualTo(TOTAL);
            }
            assertInventoryInvariant(jdbc, dropId);
        }
        System.out.printf("cancel-vs-confirm over %d runs: confirm won %d, cancel won %d%n", RUNS, confirmWins, cancelWins);
    }

    /** Many confirms and many cancels at once: still exactly one final state, consistent for every caller. */
    @Test
    void manyConfirmsAndCancelsOfOneHoldAgreeOnOneOutcome() throws Exception {
        for (int run = 1; run <= RUNS; run++) {
            long dropId = newDrop();
            UUID id = place(dropId, "alice", "k" + run);
            List<Supplier<Object>> actions = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                actions.add(() -> holdService.confirm(id, "alice"));
                actions.add(() -> holdService.cancel(id, "alice"));
            }

            List<Object> results = race(actions);

            HoldStatus finalStatus = statusOf(id);
            assertThat(finalStatus).isIn(HoldStatus.CONFIRMED, HoldStatus.CANCELLED);
            for (int i = 0; i < results.size(); i++) {
                boolean wasConfirm = i % 2 == 0;
                boolean sameAsOutcome = wasConfirm == (finalStatus == HoldStatus.CONFIRMED);
                if (sameAsOutcome) { // the winner's kind: the winner itself and its repeats all succeed
                    assertThat(results.get(i)).as("run %d request %d", run, i).isInstanceOf(Hold.class);
                    assertThat(((Hold) results.get(i)).getStatus()).isEqualTo(finalStatus);
                } else {             // the loser's kind: always rejected with the real current status
                    assertThat(results.get(i)).as("run %d request %d", run, i)
                            .isInstanceOfSatisfying(InvalidStateTransitionException.class,
                                    e -> assertThat(e.currentStatus()).isEqualTo(finalStatus));
                }
            }
            assertThat(available(dropId)).isEqualTo(finalStatus == HoldStatus.CANCELLED ? TOTAL : TOTAL - QUANTITY);
            assertInventoryInvariant(jdbc, dropId);
        }
    }

    /** Repeated cancels at the same instant: the units come back once, not once per request. */
    @Test
    void concurrentRepeatedCancelsReturnTheUnitsExactlyOnce() throws Exception {
        for (int run = 1; run <= RUNS; run++) {
            long dropId = newDrop();
            UUID id = place(dropId, "alice", "k" + run);
            List<Supplier<Object>> actions = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                actions.add(() -> holdService.cancel(id, "alice"));
            }

            List<Object> results = race(actions);

            assertThat(results).as("run %d every repeat succeeds", run).allSatisfy(r ->
                    assertThat(r).isInstanceOfSatisfying(Hold.class, h -> assertThat(h.getStatus()).isEqualTo(HoldStatus.CANCELLED)));
            assertThat(available(dropId)).as("run %d units returned once", run).isEqualTo(TOTAL);
            assertInventoryInvariant(jdbc, dropId);
        }
    }

    @Test
    void concurrentRepeatedConfirmsChangeNothingAfterTheFirst() throws Exception {
        for (int run = 1; run <= RUNS; run++) {
            long dropId = newDrop();
            UUID id = place(dropId, "alice", "k" + run);
            List<Supplier<Object>> actions = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                actions.add(() -> holdService.confirm(id, "alice"));
            }

            List<Object> results = race(actions);

            assertThat(results).allSatisfy(r ->
                    assertThat(r).isInstanceOfSatisfying(Hold.class, h -> assertThat(h.getStatus()).isEqualTo(HoldStatus.CONFIRMED)));
            assertThat(available(dropId)).isEqualTo(TOTAL - QUANTITY);
            assertInventoryInvariant(jdbc, dropId);
        }
    }

    /** Cancelling many holds of one drop in parallel, each twice: every unit returns exactly once. */
    @Test
    void cancellingManyHoldsOfOneDropInParallelReturnsEveryUnitExactlyOnce() throws Exception {
        for (int run = 1; run <= RUNS; run++) {
            long dropId = newDropWithTotal(50);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                ids.add(place(dropId, "customer-" + i, "k" + run + "-" + i));
            }
            assertThat(available(dropId)).isZero();
            List<Supplier<Object>> actions = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                UUID id = ids.get(i);
                String customer = "customer-" + i;
                actions.add(() -> holdService.cancel(id, customer));
                actions.add(() -> holdService.cancel(id, customer)); // the repeat, racing the original
            }

            List<Object> results = race(actions);

            assertThat(results).as("run %d unexpected results", run).allSatisfy(r -> assertThat(r).isInstanceOf(Hold.class));
            assertThat(available(dropId)).as("run %d available after cancelling all", run).isEqualTo(50);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM holds WHERE drop_id = ? AND status = 'CANCELLED'", Integer.class, dropId)).isEqualTo(50);
            assertInventoryInvariant(jdbc, dropId);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Runs all actions at the same instant; returns each action's result or the exception it threw, in order. */
    private List<Object> race(List<? extends Supplier<Object>> actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Supplier<Object> action : actions) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return action.get();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long newDrop() {
        return newDropWithTotal(TOTAL);
    }

    private long newDropWithTotal(int total) {
        Instant now = clock.instant();
        return drops.save(Drop.create("Race", null, total, Math.min(QUANTITY, total), now.minusSeconds(60), now)).getId();
    }

    private UUID place(long dropId, String customer, String key) {
        int quantity = jdbc.queryForObject("SELECT total_quantity FROM drops WHERE id = ?", Integer.class, dropId) == TOTAL
                ? QUANTITY : 1;
        return holdService.placeHold(new PlaceHoldCommand(dropId, customer, key, quantity)).hold().getId();
    }

    private HoldStatus statusOf(UUID id) {
        return HoldStatus.valueOf(jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, id.toString()));
    }

    private int available(long dropId) {
        return jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId);
    }
}
