package com.kibo.reservation.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis is only a cache (FR-027, SC-005): when it is unreachable or hangs, every read and every hold,
 * cancel and confirm still works and returns MySQL's truth, with no meaningful added delay, and caching
 * resumes by itself once Redis is back. A hung Redis is simulated by pausing its container (the harshest
 * case: connections stay open but nothing answers). A refused connection is what every other integration
 * test already runs with (Redis pointed at a closed port).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DropCacheOutageIT extends AbstractMySqlIT {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void ownRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

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
        dropId = drops.save(Drop.create("Open", null, 5, 4, now.minusSeconds(60), now)).getId();
    }

    @Test
    void aHungRedisNeverBreaksOrMeaningfullySlowsReadsAndHoldsAndCachingResumesAfterwards() throws Exception {
        // healthy: the first reads fill the cache (polled, because the very first connection may be slow)
        assertThat(DropCacheIT.awaitTrue(() -> {
            availableViaApiQuietly();
            return Boolean.TRUE.equals(redis.hasKey("kibo:drops:" + dropId));
        }, Duration.ofSeconds(15))).as("cache filled while Redis is healthy").isTrue();
        assertThat(availableViaApi()).isEqualTo(5);

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            UUID id = null;
            long slowest = 0;
            // hold creation (its after-commit eviction hits the hung Redis), reads, cancel: all must work
            long t = System.nanoTime();
            id = holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2)).hold().getId();
            slowest = Math.max(slowest, System.nanoTime() - t);
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId))
                    .isEqualTo(3);

            t = System.nanoTime();
            assertThat(availableViaApi()).as("MySQL's value, not the cached 5").isEqualTo(3);
            slowest = Math.max(slowest, System.nanoTime() - t);

            holdService.cancel(id, "alice");
            for (int i = 0; i < 20; i++) { // during the outage later requests do not even try Redis
                t = System.nanoTime();
                assertThat(availableViaApi()).isEqualTo(5);
                slowest = Math.max(slowest, System.nanoTime() - t);
            }
            // The worst single request may wait for one Redis timeout (0.5 s connect / 0.2 s command); the rest are instant.
            assertThat(Duration.ofNanos(slowest)).as("slowest request during the outage").isLessThan(Duration.ofSeconds(2));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        // Redis is back: after the short bypass window the cache fills again by itself.
        boolean resumed = DropCacheIT.awaitTrue(() -> {
            availableViaApiQuietly();
            return Boolean.TRUE.equals(redis.hasKey("kibo:drops:" + dropId));
        }, Duration.ofSeconds(15));
        assertThat(resumed).as("caching resumed after Redis came back").isTrue();
        assertThat(redis.getExpire("kibo:drops:" + dropId, TimeUnit.SECONDS)).isPositive();
    }

    private int availableViaApi() throws Exception {
        String body = mvc.perform(get("/api/v1/drops/{id}", dropId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.availableQuantity");
    }

    private void availableViaApiQuietly() {
        try {
            availableViaApi();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
