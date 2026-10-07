package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kibo.reservation.application.HoldPlacement;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.exception.IdempotencyKeyConflictException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** SC-003: retries with the same request key never consume inventory twice, even when truly concurrent. */
class IdempotentHoldIT extends AbstractMySqlIT {

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
        dropId = drops.save(Drop.create("Idempotent", null, 20, 4, now.minusSeconds(60), now)).getId();
    }

    @Test
    void tenConcurrentIdenticalRequestsCreateExactlyOneHold() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<HoldPlacement>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "retry-key", 3));
                }));
            }
            go.countDown();
            List<HoldPlacement> results = new ArrayList<>();
            for (Future<HoldPlacement> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(results).extracting(p -> p.hold().getId()).containsOnly(results.get(0).hold().getId());
            assertThat(results).filteredOn(HoldPlacement::created).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId))
                    .isEqualTo(17);
            assertInventoryInvariant(jdbc, dropId);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sequentialRetryReplaysAndDifferentDetailsConflict() {
        HoldPlacement first = holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2));
        HoldPlacement retry = holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2));

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.hold().getId()).isEqualTo(first.hold().getId());
        assertThatThrownBy(() -> holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 3)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        // The same key from another customer is a different request.
        assertThat(holdService.placeHold(new PlaceHoldCommand(dropId, "bob", "k1", 1)).created()).isTrue();

        assertThat(jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId))
                .isEqualTo(17);
        assertInventoryInvariant(jdbc, dropId);
    }
}
