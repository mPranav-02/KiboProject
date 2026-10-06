package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.kibo.reservation.application.HoldExpirationService;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.exception.InsufficientInventoryException;
import com.kibo.reservation.repository.DropRepository;
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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

/**
 * The drop read cache against a REAL Redis (Testcontainers): what is cached and for how long, that every
 * availability-changing commit evicts, that other outcomes do not, and that MySQL stays the truth.
 * Redis down / hanging is in {@link DropCacheOutageIT}.
 */
// TTL 2 s so expiry can be observed. The Redis command timeout is relaxed from the 200 ms production default so a
// slow CI machine cannot trip the (correct, but test-disrupting) cache bypass window.
@TestPropertySource(properties = {"kibo.cache.drop-ttl=PT2S", "spring.data.redis.timeout=2s"})
class DropCacheIT extends AbstractMySqlIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void realRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static final int TOTAL = 5;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldExpirationService expiration;

    @Autowired
    private DropRepository drops;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long dropId;
    private long otherDropId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        Instant now = clock.instant();
        dropId = drops.save(Drop.create("Open", null, TOTAL, 4, now.minusSeconds(60), now)).getId();
        otherDropId = drops.save(Drop.create("Other", null, TOTAL, 4, now.minusSeconds(60), now)).getId();
    }

    // ---------------------------------------------------------- what is cached

    @Test
    void aReadPopulatesRedisWithAJsonSnapshotThatExpiresAfterTheTtl() throws Exception {
        assertThat(availableViaApi(dropId)).isEqualTo(TOTAL);

        String key = "kibo:drops:" + dropId;
        assertThat(redis.hasKey(key)).isTrue();
        Long ttlMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).isBetween(1L, 2_000L);
        String json = redis.opsForValue().get(key);
        assertThat(JsonPath.<Integer>read(json, "$.availableQuantity")).isEqualTo(TOTAL);
        assertThat(JsonPath.<Integer>read(json, "$.totalQuantity")).isEqualTo(TOTAL);
        assertThat(json).doesNotContain("availabilityStatus"); // time-dependent, computed on every read

        listViaApi();
        assertThat(redis.hasKey("kibo:drops:all")).isTrue();
        assertThat(redis.getExpire("kibo:drops:all", TimeUnit.MILLISECONDS)).isBetween(1L, 2_000L);
    }

    @Test
    void anUnknownDropIsNeverCached() throws Exception {
        mvc.perform(get("/api/v1/drops/{id}", 987654L)).andExpect(status().isNotFound());

        assertThat(redis.keys("kibo:drops:*")).isEmpty();
    }

    @Test
    void theAvailabilityStatusIsComputedAfterTheCacheReadSoADropOpensOnTimeEvenFromACachedEntry() throws Exception {
        Instant now = clock.instant();
        long upcoming = drops.save(Drop.create("Soon", null, TOTAL, 4, now.plusMillis(900), now)).getId();

        assertThat(statusViaApi(upcoming)).isEqualTo("UPCOMING");
        Thread.sleep(1_100); // now past startsAt, but inside the 2 s TTL
        assertThat(redis.hasKey("kibo:drops:" + upcoming)).as("still served from the cache").isTrue();

        assertThat(statusViaApi(upcoming)).isEqualTo("OPEN");
    }

    // ---------------------------------------------------- MySQL stays the truth

    @Test
    void aCachedValueIsServedUntilItExpiresAndThenMySqlIsReadAgain() throws Exception {
        assertThat(availableViaApi(dropId)).isEqualTo(TOTAL);
        jdbc.update("UPDATE drops SET available_quantity = 1 WHERE id = ?", dropId); // behind the cache's back

        assertThat(availableViaApi(dropId)).as("served from the cache, so stale").isEqualTo(TOTAL);

        Instant start = clock.instant();
        assertThat(awaitTrue(() -> availableViaApiQuietly(dropId) == 1, Duration.ofSeconds(6)))
                .as("fresh value after the entry expired").isTrue();
        assertThat(Duration.between(start, clock.instant())).as("staleness bounded by the TTL")
                .isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void theHoldDecisionIgnoresAStaleCacheAndUsesMySql() throws Exception {
        assertThat(availableViaApi(dropId)).isEqualTo(TOTAL); // the cache says 5
        jdbc.update("UPDATE drops SET available_quantity = 0 WHERE id = ?", dropId); // MySQL says sold out

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 1)))
                .isInstanceOf(InsufficientInventoryException.class); // refused although the cache says 5
        assertThat(availableViaApi(dropId)).as("the refusal did not touch the cache").isEqualTo(TOTAL);

        jdbc.update("UPDATE drops SET available_quantity = 2 WHERE id = ?", dropId); // MySQL says 2 again
        holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k2", 2)); // granted: MySQL decides
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId)).isZero();
        assertThat(availableViaApi(dropId)).as("and the commit evicted the stale entry").isZero();
    }

    // ------------------------------------------------------ eviction after commit

    @Test
    void creatingAHoldEvictsTheDropAndTheListSoTheNextReadIsFresh() throws Exception {
        assertThat(availableViaApi(dropId)).isEqualTo(TOTAL);
        assertThat(availableViaApi(otherDropId)).isEqualTo(TOTAL);
        listViaApi();

        holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2));

        assertThat(redis.hasKey("kibo:drops:" + dropId)).isFalse();
        assertThat(redis.hasKey("kibo:drops:all")).isFalse();
        assertThat(redis.hasKey("kibo:drops:" + otherDropId)).as("other drops stay cached").isTrue();
        assertThat(availableViaApi(dropId)).as("no waiting for the TTL").isEqualTo(TOTAL - 2);
    }

    @Test
    void cancellingAHoldEvictsSoTheReturnedUnitsAreVisibleImmediately() throws Exception {
        UUID id = holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 3)).hold().getId();
        assertThat(availableViaApi(dropId)).isEqualTo(TOTAL - 3);

        holdService.cancel(id, "alice");

        assertThat(redis.hasKey("kibo:drops:" + dropId)).isFalse();
        assertThat(availableViaApi(dropId)).isEqualTo(TOTAL);
    }

    @Test
    void expiringAHoldEvictsSoTheReturnedUnitsAreVisibleImmediately() throws Exception {
        UUID id = holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 3)).hold().getId();
        jdbc.update("UPDATE holds SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id.toString());
        assertThat(availableViaApi(dropId)).isEqualTo(TOTAL - 3);

        expiration.expireOverdue(clock.instant());

        assertThat(redis.hasKey("kibo:drops:" + dropId)).isFalse();
        assertThat(availableViaApi(dropId)).isEqualTo(TOTAL);
    }

    @Test
    void confirmingReplayingAndRejectedRequestsLeaveTheCacheAlone() throws Exception {
        UUID id = holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2)).hold().getId();
        assertThat(availableViaApi(dropId)).isEqualTo(TOTAL - 2);
        String key = "kibo:drops:" + dropId;

        holdService.confirm(id, "alice"); // units stay consumed: nothing to refresh
        assertThat(redis.hasKey(key)).as("after confirm").isTrue();

        holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2)); // replay: nothing changed
        assertThat(redis.hasKey(key)).as("after replay").isTrue();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        holdService.placeHold(new PlaceHoldCommand(dropId, "bob", "k2", 4)))  // only 3 left: rolled back
                .isInstanceOf(InsufficientInventoryException.class);
        assertThat(redis.hasKey(key)).as("after a rejected request").isTrue();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> holdService.cancel(id, "alice")) // confirmed: rejected
                .isInstanceOf(com.kibo.reservation.domain.exception.InvalidStateTransitionException.class);
        assertThat(redis.hasKey(key)).as("after a rejected cancel").isTrue();
    }

    // --------------------------------------------- under load: bounded staleness

    @Test
    void underConcurrentHoldsAndReadsMySqlNeverOversellsAndTheCacheCatchesUpWithinTheTtl() throws Exception {
        long raceDrop = drops.save(Drop.create("Race", null, 50, 4, clock.instant().minusSeconds(60), clock.instant())).getId();
        int requests = 200;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> placements = new ArrayList<>();
            List<Future<?>> readers = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                int n = i;
                placements.add(pool.submit(() -> {
                    go.await();
                    try {
                        holdService.placeHold(new PlaceHoldCommand(raceDrop, "c" + n, "k" + n, 1));
                        return true;
                    } catch (InsufficientInventoryException e) {
                        return false;
                    }
                }));
                readers.add(pool.submit(() -> { // readers hammering the cached endpoint while holds are being placed
                    go.await();
                    for (int r = 0; r < 20; r++) {
                        mvc.perform(get("/api/v1/drops/{id}", raceDrop)).andExpect(status().isOk());
                    }
                    return null;
                }));
            }
            go.countDown();
            int granted = 0;
            for (Future<Boolean> f : placements) {
                granted += f.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }

            for (Future<?> reader : readers) {
                reader.get(60, TimeUnit.SECONDS); // let every read finish: none may be cut off mid-Redis-call
            }

            assertThat(granted).as("MySQL decided: exactly the stock, never more").isEqualTo(50);
            assertInventoryInvariant(jdbc, raceDrop);
            // A reader may have re-cached a pre-commit value just after the last eviction. That stale entry can
            // live at most one TTL (2 s); after that every read agrees with MySQL.
            assertThat(awaitTrue(() -> availableViaApiQuietly(raceDrop) == 0, Duration.ofSeconds(6)))
                    .as("cache converged to MySQL within the TTL").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    private int availableViaApi(long id) throws Exception {
        String body = mvc.perform(get("/api/v1/drops/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.availableQuantity");
    }

    private int availableViaApiQuietly(long id) {
        try {
            return availableViaApi(id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String statusViaApi(long id) throws Exception {
        String body = mvc.perform(get("/api/v1/drops/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.availabilityStatus");
    }

    private void listViaApi() throws Exception {
        mvc.perform(get("/api/v1/drops")).andExpect(status().isOk());
    }

    static boolean awaitTrue(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
