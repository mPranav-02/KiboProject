package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;

import com.kibo.reservation.application.HoldExpirationService;
import com.kibo.reservation.application.HoldExpirationService.Outcome;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.config.KiboProperties;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.exception.HoldExpiredException;
import com.kibo.reservation.domain.exception.InvalidStateTransitionException;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Expiration racing confirm and cancel on the very same hold (FR-025, SC-002). Many repetitions
 * ({@code -Dkibo.it.raceRuns=N}, default 200; use 1000 for the full SC-002 run).
 *
 * <p><b>How the race is forced right at the boundary.</b> The requests use clocks on either side of the
 * hold's {@code expiresAt}, as two instances with slightly skewed clocks would: confirm/cancel run as of
 * 1 ms BEFORE expiry (valid), the sweeper as of 1 ms AFTER (due). Both are therefore entitled to win, and
 * only MySQL's guarded UPDATE decides. Whoever loses must be rejected or have no effect.
 *
 * <p><b>How a double return of units is made visible.</b> Every drop also holds an already-confirmed filler
 * hold, so availability is never at the drop's total: the drop-side "never above total" safety net cannot
 * hide a second return, and the inventory invariant (total - available == units in ACTIVE + CONFIRMED
 * holds) catches it.
 */
class HoldRaceIT extends AbstractMySqlIT {

    private static final int RACE_RUNS = Integer.getInteger("kibo.it.raceRuns", 200);
    private static final int TOTAL = 10;
    private static final int FILLER = 3;
    private static final int QUANTITY = 4;

    @Autowired
    private HoldService realHoldService;

    @Autowired
    private DropRepository drops;

    @Autowired
    private HoldRepository holds;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KiboProperties properties;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private HoldExpirationService expiration;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
        expiration = new HoldExpirationService(drops, holds, transactionManager, properties, events);
        pool = Executors.newFixedThreadPool(15);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void confirmVersusExpireHasExactlyOneWinner() throws Exception {
        Map<HoldStatus, Integer> wins = new EnumMap<>(HoldStatus.class);
        for (int run = 1; run <= RACE_RUNS; run++) {
            Fixture f = newHold(run);

            List<Object> r = race(
                    () -> f.confirmAsOfBeforeExpiry(),
                    () -> expiration.expireOne(f.holdId, f.afterExpiry));

            HoldStatus finalStatus = f.finalStatus();
            wins.merge(finalStatus, 1, Integer::sum);
            if (finalStatus == HoldStatus.CONFIRMED) {
                assertThat(r.get(0)).as("run %d confirm", run).isInstanceOf(Hold.class);
                assertThat(r.get(1)).as("run %d expire", run).isEqualTo(Outcome.SKIPPED);
                assertThat(f.available()).as("run %d confirmed units stay consumed", run).isEqualTo(TOTAL - FILLER - QUANTITY);
            } else {
                assertThat(finalStatus).as("run %d", run).isEqualTo(HoldStatus.EXPIRED);
                assertThat(r.get(0)).as("run %d confirm", run).isInstanceOf(HoldExpiredException.class);
                assertThat(r.get(1)).as("run %d expire", run).isEqualTo(Outcome.EXPIRED);
                assertThat(f.available()).as("run %d expired units returned once", run).isEqualTo(TOTAL - FILLER);
            }
            assertInventoryInvariant(jdbc, f.dropId);
        }
        System.out.printf("confirm-vs-expire over %d runs: %s%n", RACE_RUNS, wins);
    }

    @Test
    void cancelVersusExpireHasExactlyOneWinnerAndUnitsReturnExactlyOnce() throws Exception {
        Map<HoldStatus, Integer> wins = new EnumMap<>(HoldStatus.class);
        for (int run = 1; run <= RACE_RUNS; run++) {
            Fixture f = newHold(run);

            List<Object> r = race(
                    () -> f.cancelAsOfBeforeExpiry(),
                    () -> expiration.expireOne(f.holdId, f.afterExpiry));

            HoldStatus finalStatus = f.finalStatus();
            wins.merge(finalStatus, 1, Integer::sum);
            if (finalStatus == HoldStatus.CANCELLED) {
                assertThat(r.get(0)).as("run %d cancel", run).isInstanceOf(Hold.class);
                assertThat(r.get(1)).as("run %d expire", run).isEqualTo(Outcome.SKIPPED);
            } else {
                assertThat(finalStatus).as("run %d", run).isEqualTo(HoldStatus.EXPIRED);
                assertThat(r.get(0)).as("run %d cancel", run).isInstanceOf(HoldExpiredException.class);
                assertThat(r.get(1)).as("run %d expire", run).isEqualTo(Outcome.EXPIRED);
            }
            // Either way the units come back once: a second return would show as available > TOTAL - FILLER.
            assertThat(f.available()).as("run %d units returned exactly once", run).isEqualTo(TOTAL - FILLER);
            assertInventoryInvariant(jdbc, f.dropId);
        }
        System.out.printf("cancel-vs-expire over %d runs: %s%n", RACE_RUNS, wins);
    }

    /** Confirms, cancels and expiries all at once, several of each: still exactly one final state. */
    @Test
    void confirmCancelAndExpireTogetherAgreeOnOneOutcome() throws Exception {
        Map<HoldStatus, Integer> wins = new EnumMap<>(HoldStatus.class);
        for (int run = 1; run <= RACE_RUNS; run++) {
            Fixture f = newHold(run);
            List<Callable<Object>> actions = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                actions.add(() -> f.confirmAsOfBeforeExpiry());
                actions.add(() -> f.cancelAsOfBeforeExpiry());
                actions.add(() -> expiration.expireOne(f.holdId, f.afterExpiry));
            }

            List<Object> r = race(actions.toArray(Callable[]::new));

            HoldStatus x = f.finalStatus();
            wins.merge(x, 1, Integer::sum);
            int expiredOutcomes = 0;
            for (int i = 0; i < r.size(); i++) {
                Object result = r.get(i);
                switch (i % 3) {
                    case 0 -> { // confirm
                        switch (x) {
                            case CONFIRMED -> assertThat(result).isInstanceOfSatisfying(Hold.class,
                                    h -> assertThat(h.getStatus()).isEqualTo(HoldStatus.CONFIRMED));
                            case CANCELLED -> assertThat(result).isInstanceOfSatisfying(InvalidStateTransitionException.class,
                                    e -> assertThat(e.currentStatus()).isEqualTo(HoldStatus.CANCELLED));
                            default -> assertThat(result).isInstanceOf(HoldExpiredException.class);
                        }
                    }
                    case 1 -> { // cancel
                        switch (x) {
                            case CANCELLED -> assertThat(result).isInstanceOfSatisfying(Hold.class,
                                    h -> assertThat(h.getStatus()).isEqualTo(HoldStatus.CANCELLED));
                            case CONFIRMED -> assertThat(result).isInstanceOfSatisfying(InvalidStateTransitionException.class,
                                    e -> assertThat(e.currentStatus()).isEqualTo(HoldStatus.CONFIRMED));
                            default -> assertThat(result).isInstanceOf(HoldExpiredException.class);
                        }
                    }
                    default -> { // expire
                        assertThat(result).isInstanceOf(Outcome.class);
                        if (result == Outcome.EXPIRED) {
                            expiredOutcomes++;
                        }
                    }
                }
            }
            assertThat(expiredOutcomes).as("run %d: expired by exactly one sweeper iff the hold ended EXPIRED", run)
                    .isEqualTo(x == HoldStatus.EXPIRED ? 1 : 0);
            assertThat(f.available()).as("run %d available", run)
                    .isEqualTo(x == HoldStatus.CONFIRMED ? TOTAL - FILLER - QUANTITY : TOTAL - FILLER);
            assertInventoryInvariant(jdbc, f.dropId);
        }
        System.out.printf("confirm+cancel+expire over %d runs: %s%n", RACE_RUNS, wins);
    }

    // ---------------------------------------------------------------- helpers

    /** A fresh drop with a confirmed filler hold and the ACTIVE hold under race, plus skewed-clock services. */
    private Fixture newHold(int run) {
        Instant now = clock.instant();
        long dropId = drops.save(Drop.create("Race " + run, null, TOTAL, QUANTITY, now.minusSeconds(60), now)).getId();
        UUID filler = realHoldService.placeHold(new PlaceHoldCommand(dropId, "filler", "filler-" + run, FILLER)).hold().getId();
        realHoldService.confirm(filler, "filler");
        UUID id = realHoldService.placeHold(new PlaceHoldCommand(dropId, "alice", "k-" + run, QUANTITY)).hold().getId();
        return new Fixture(dropId, id, holds.findById(id).orElseThrow().getExpiresAt());
    }

    @SafeVarargs
    private List<Object> race(Callable<Object>... actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.length);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> action : actions) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return action.call();
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
    }

    private final class Fixture {
        final long dropId;
        final UUID holdId;
        final Instant afterExpiry;
        private final HoldService beforeExpiryService;

        Fixture(long dropId, UUID holdId, Instant expiresAt) {
            this.dropId = dropId;
            this.holdId = holdId;
            this.afterExpiry = expiresAt.plusMillis(1);
            this.beforeExpiryService = new HoldService(drops, holds, transactionManager,
                    Clock.fixed(expiresAt.minusMillis(1), ZoneOffset.UTC), properties, expiration, events);
        }

        Object confirmAsOfBeforeExpiry() {
            return beforeExpiryService.confirm(holdId, "alice");
        }

        Object cancelAsOfBeforeExpiry() {
            return beforeExpiryService.cancel(holdId, "alice");
        }

        HoldStatus finalStatus() {
            return HoldStatus.valueOf(jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, holdId.toString()));
        }

        int available() {
            return jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId);
        }
    }
}
