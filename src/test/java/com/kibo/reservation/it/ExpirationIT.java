package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kibo.reservation.application.HoldExpirationService;
import com.kibo.reservation.application.HoldExpirationService.Outcome;
import com.kibo.reservation.application.HoldExpirationService.Summary;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.config.KiboProperties;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.exception.HoldExpiredException;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US3 against real MySQL: overdue ACTIVE holds expire and their units come back exactly once, even with
 * several sweepers running at the same time. The sweeps here are driven explicitly; the scheduler itself is
 * proven in {@link HoldExpirationJobIT}, and races with confirm and cancel in {@link HoldRaceIT}.
 */
class ExpirationIT extends AbstractMySqlIT {

    private static final int RUNS = Integer.getInteger("kibo.it.runs", 10);
    private static final int TOTAL = 10;

    @Autowired
    private HoldExpirationService expiration;

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldRepository holds;

    @Autowired
    private DropRepository drops;

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

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
    }

    @Test
    void overdueHoldsExpireAndTheirUnitsAreRestoredWhileOthersAreUntouched() {
        long dropId = newDrop(TOTAL, 4);
        UUID overdue1 = place(dropId, "a", 2);
        UUID overdue2 = place(dropId, "b", 3);
        UUID notDue = place(dropId, "c", 1);
        makeOverdue(overdue1, overdue2);
        assertThat(available(dropId)).isEqualTo(TOTAL - 6);

        Summary summary = expiration.expireOverdue(clock.instant());

        assertThat(summary).isEqualTo(new Summary(2, 2, 0));
        assertThat(status(overdue1)).isEqualTo("EXPIRED");
        assertThat(status(overdue2)).isEqualTo("EXPIRED");
        assertThat(status(notDue)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT resolved_at IS NOT NULL FROM holds WHERE id = ?", Boolean.class,
                overdue1.toString())).isTrue();
        assertThat(available(dropId)).as("only the 2 + 3 expired units came back").isEqualTo(TOTAL - 1);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void confirmedAndCancelledHoldsPastTheirExpiryAreNeverExpiredAndNeverReturnUnitsAgain() {
        long dropId = newDrop(TOTAL, 4);
        UUID confirmed = place(dropId, "a", 3);
        UUID cancelled = place(dropId, "b", 2);
        UUID active = place(dropId, "c", 1);
        holdService.confirm(confirmed, "a");
        holdService.cancel(cancelled, "b");
        makeOverdue(confirmed, cancelled, active);

        Summary summary = expiration.expireOverdue(clock.instant());

        assertThat(summary).isEqualTo(new Summary(1, 1, 0)); // only the ACTIVE one was even a candidate
        assertThat(status(confirmed)).isEqualTo("CONFIRMED");
        assertThat(status(cancelled)).isEqualTo("CANCELLED");
        assertThat(status(active)).isEqualTo("EXPIRED");
        assertThat(available(dropId)).as("confirmed units stay consumed").isEqualTo(TOTAL - 3);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void repeatedSweepsAreNoOpsAfterTheFirst() {
        long dropId = newDrop(TOTAL, 4);
        UUID id = place(dropId, "a", 4);
        makeOverdue(id);

        assertThat(expiration.expireOverdue(clock.instant()).expired()).isEqualTo(1);
        for (int i = 0; i < 5; i++) {
            assertThat(expiration.expireOverdue(clock.instant())).isEqualTo(new Summary(0, 0, 0));
        }
        assertThat(expiration.expireOne(id, clock.instant())).isEqualTo(Outcome.SKIPPED); // direct repeat

        assertThat(available(dropId)).isEqualTo(TOTAL);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void threeConcurrentSweepersAndRepeatedSweepsReturnEachHoldsUnitsExactlyOnce() throws Exception {
        for (int run = 1; run <= RUNS; run++) {
            long dropId = newDrop(60, 1);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                ids.add(place(dropId, "c" + run + "-" + i, 1));
            }
            makeOverdue(ids.toArray(UUID[]::new));
            assertThat(available(dropId)).isZero();

            List<Summary> summaries = sweepConcurrently(3, 2);

            int expiredBySweepers = summaries.stream().mapToInt(Summary::expired).sum();
            assertThat(expiredBySweepers).as("run %d: each hold expired by exactly one sweeper", run).isEqualTo(60);
            assertThat(summaries).as("run %d: no sweep failed", run).allSatisfy(s -> assertThat(s.failed()).isZero());
            assertThat(available(dropId)).as("run %d: units returned once each", run).isEqualTo(60);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds WHERE drop_id = ? AND status = 'EXPIRED'",
                    Integer.class, dropId)).isEqualTo(60);
            assertInventoryInvariant(jdbc, dropId);
        }
    }

    @Test
    void holdsOverdueSinceBeforeARestartAreExpiredByTheFirstSweep() {
        long dropId = newDrop(TOTAL, 4);
        UUID id = place(dropId, "a", 4);
        jdbc.update("UPDATE holds SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 3 DAY WHERE id = ?", id.toString());

        assertThat(expiration.expireOverdue(clock.instant()).expired()).isEqualTo(1);

        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(available(dropId)).isEqualTo(TOTAL);
    }

    @Test
    void confirmAndCancelAfterExpiryAreRejectedAndChangeNothing() {
        long dropId = newDrop(TOTAL, 4);
        UUID id = place(dropId, "a", 4);
        makeOverdue(id);
        expiration.expireOverdue(clock.instant());

        assertThatThrownBy(() -> holdService.confirm(id, "a")).isInstanceOf(HoldExpiredException.class);
        assertThatThrownBy(() -> holdService.cancel(id, "a")).isInstanceOf(HoldExpiredException.class);

        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(available(dropId)).isEqualTo(TOTAL);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void theExpiryBoundaryIsInclusiveInTheDatabaseGuard() {
        long dropId = newDrop(TOTAL, 4);
        UUID id = place(dropId, "a", 2);
        Instant expiresAt = holds.findById(id).orElseThrow().getExpiresAt();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThat(expireAt(tx, id, expiresAt.minusNanos(1_000))).as("1 microsecond before").isZero();
        assertThat(expiration.expireOne(id, expiresAt.minusNanos(1_000))).isEqualTo(Outcome.SKIPPED);
        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(available(dropId)).isEqualTo(TOTAL - 2);

        assertThat(expiration.expireOne(id, expiresAt)).as("at exactly expiresAt").isEqualTo(Outcome.EXPIRED);
        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(available(dropId)).isEqualTo(TOTAL);
    }

    @Test
    void aSweepLargerThanOneBatchKeepsGoingUntilNothingIsOverdue() {
        long dropId = newDrop(23, 1);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            ids.add(place(dropId, "c" + i, 1));
        }
        makeOverdue(ids.toArray(UUID[]::new));
        HoldExpirationService smallBatches = new HoldExpirationService(drops, holds, transactionManager,
                new KiboProperties(properties.hold(),
                        new KiboProperties.ExpirationSettings(Duration.ofSeconds(2), 5),
                        properties.cache(), properties.seed()), events);

        Summary summary = smallBatches.expireOverdue(clock.instant());

        assertThat(summary.expired()).isEqualTo(23);
        assertThat(available(dropId)).isEqualTo(23);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void aHoldWhoseUnitsCannotBeReturnedStaysActiveAndDoesNotStopTheRestOfTheSweep() {
        long goodDrop = newDrop(TOTAL, 4);
        UUID good = place(goodDrop, "a", 2);
        makeOverdue(good);
        // Corrupt data on purpose: an overdue ACTIVE hold whose drop is already fully available. Returning its
        // unit would push availability above the total, which must be refused, not silently allowed.
        long brokenDrop = newDrop(TOTAL, 4);
        jdbc.update("""
                INSERT INTO holds (id, drop_id, customer_id, request_key, quantity, status, expires_at, created_at, updated_at)
                VALUES ('00000000-0000-0000-0000-000000000001', ?, 'x', 'kx', 1, 'ACTIVE',
                        UTC_TIMESTAMP(6) - INTERVAL 1 SECOND, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, brokenDrop);

        Summary summary = expiration.expireOverdue(clock.instant());

        assertThat(summary).isEqualTo(new Summary(2, 1, 1));
        assertThat(status(good)).isEqualTo("EXPIRED");
        assertThat(available(goodDrop)).isEqualTo(TOTAL);
        assertThat(jdbc.queryForObject("SELECT status FROM holds WHERE id = '00000000-0000-0000-0000-000000000001'",
                String.class)).as("status change rolled back with the failed return").isEqualTo("ACTIVE");
        assertThat(available(brokenDrop)).isEqualTo(TOTAL);
    }

    // ---------------------------------------------------------------- helpers

    private List<Summary> sweepConcurrently(int sweepers, int sweepsEach) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(sweepers);
        CountDownLatch ready = new CountDownLatch(sweepers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<List<Summary>>> futures = new ArrayList<>();
            for (int i = 0; i < sweepers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    List<Summary> results = new ArrayList<>();
                    for (int n = 0; n < sweepsEach; n++) {
                        results.add(expiration.expireOverdue(clock.instant()));
                    }
                    return results;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Summary> all = new ArrayList<>();
            for (Future<List<Summary>> f : futures) {
                all.addAll(f.get(120, TimeUnit.SECONDS));
            }
            return all;
        } finally {
            pool.shutdownNow();
        }
    }

    private long newDrop(int total, int maxPerHold) {
        Instant now = clock.instant();
        return drops.save(Drop.create("Drop", null, total, maxPerHold, now.minusSeconds(60), now)).getId();
    }

    private UUID place(long dropId, String customer, int quantity) {
        return holdService.placeHold(new PlaceHoldCommand(dropId, customer, "key-" + customer, quantity)).hold().getId();
    }

    private void makeOverdue(UUID... ids) {
        for (UUID id : ids) {
            jdbc.update("UPDATE holds SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id.toString());
        }
    }

    private int expireAt(TransactionTemplate tx, UUID id, Instant now) {
        return tx.execute(s -> holds.expire(id, now));
    }

    private int available(long dropId) {
        return jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, id.toString());
    }
}
