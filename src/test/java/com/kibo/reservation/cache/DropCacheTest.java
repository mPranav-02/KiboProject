package com.kibo.reservation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kibo.reservation.application.DropSnapshot;
import com.kibo.reservation.config.KiboProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * DropCache with a mocked Redis template: key names, TTL, JSON shape, and above all the "fail open" behavior.
 * Behavior against a real Redis is in {@code DropCacheIT} and {@code DropCacheOutageIT}.
 */
class DropCacheTest {

    private static final Instant T0 = Instant.parse("2026-10-06T10:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(3);
    private static final DropSnapshot SNAPSHOT = new DropSnapshot(7L, "Seven", "desc", 10, 4, 4, T0);

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private MutableClock clock;
    private DropCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        clock = new MutableClock(T0);
        cache = cacheWithTtl(TTL);
    }

    private DropCache cacheWithTtl(Duration ttl) {
        KiboProperties properties = new KiboProperties(
                new KiboProperties.HoldSettings(Duration.ofMinutes(5), 4),
                new KiboProperties.ExpirationSettings(Duration.ofSeconds(2), 200),
                new KiboProperties.CacheSettings(ttl),
                new KiboProperties.SeedSettings(false));
        return new DropCache(redis, mapper, properties, clock);
    }

    // ------------------------------------------------------------- normal use

    @Test
    void putStoresAJsonSnapshotUnderTheDropKeyWithTheConfiguredTtl() {
        cache.putDrop(SNAPSHOT);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("kibo:drops:7"), json.capture(), eq(TTL));
        assertThat(json.getValue())
                .contains("\"id\":7", "\"availableQuantity\":4", "\"startsAt\":\"2026-10-06T10:00:00Z\"")
                .as("the time-dependent status is never cached").doesNotContain("availabilityStatus");
    }

    @Test
    void putAllStoresTheWholeListUnderTheListKeyWithTheConfiguredTtl() {
        cache.putAll(List.of(SNAPSHOT));

        verify(values).set(eq("kibo:drops:all"), any(String.class), eq(TTL));
    }

    @Test
    void aStoredSnapshotIsReadBackExactly() throws Exception {
        when(values.get("kibo:drops:7")).thenReturn(mapper.writeValueAsString(SNAPSHOT));
        when(values.get("kibo:drops:all")).thenReturn(mapper.writeValueAsString(List.of(SNAPSHOT, SNAPSHOT)));

        assertThat(cache.findDrop(7L)).contains(SNAPSHOT);
        assertThat(cache.findAll()).contains(List.of(SNAPSHOT, SNAPSHOT));
    }

    @Test
    void aMissIsAnEmptyOptional() {
        when(values.get(anyString())).thenReturn(null);

        assertThat(cache.findDrop(7L)).isEmpty();
        assertThat(cache.findAll()).isEmpty();
    }

    @Test
    void evictRemovesTheDropAndTheListInOneCall() {
        cache.evict(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> keys = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(redis).delete(keys.capture());
        assertThat(keys.getValue()).containsExactlyInAnyOrder("kibo:drops:7", "kibo:drops:all");
    }

    // ------------------------------------------------------------ fail open

    @Test
    void ifRedisFailsOnReadItIsJustAMiss() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatCode(() -> cache.findDrop(7L)).doesNotThrowAnyException();
        assertThat(cache.findDrop(7L)).isEmpty();
    }

    @Test
    void ifRedisFailsOnWriteOrEvictTheCallerNeverSeesIt() {
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> cache.putDrop(SNAPSHOT)).doesNotThrowAnyException();
        clock.advance(Duration.ofSeconds(6)); // past the bypass, so evict really tries
        assertThatCode(() -> cache.evict(7L)).doesNotThrowAnyException();
    }

    @Test
    void afterAFailureRedisIsLeftAloneUntilTheBypassWindowEnds() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("timeout"));
        cache.findDrop(7L); // first failure starts the bypass window
        verify(values, times(1)).get(anyString());

        // within the window every operation is skipped: one slow call, not one per request
        cache.findDrop(7L);
        cache.findAll();
        cache.putDrop(SNAPSHOT);
        cache.putAll(List.of(SNAPSHOT));
        cache.evict(7L);
        verify(values, times(1)).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(redis, never()).delete(anyCollection());

        // once the window is over Redis is tried again, and works again
        clock.advance(Duration.ofSeconds(5));
        org.mockito.Mockito.doReturn(null).when(values).get(anyString());
        cache.findDrop(7L);
        verify(values, times(2)).get(anyString());
    }

    @Test
    void theBypassWindowIsNeverShorterThanTheTtlSoNoEntryWrittenBeforeAFailureOutlivesIt() {
        DropCache longTtl = cacheWithTtl(Duration.ofSeconds(30));
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("timeout"));
        longTtl.findDrop(7L);

        clock.advance(Duration.ofSeconds(29)); // longer than the 5 s minimum, shorter than the TTL
        longTtl.findDrop(7L);
        verify(values, times(1)).get(anyString());

        clock.advance(Duration.ofSeconds(2));
        longTtl.findDrop(7L);
        verify(values, times(2)).get(anyString());
    }

    @Test
    void anUnreadableEntryIsAMissIsRemovedAndDoesNotStartTheBypass() {
        when(values.get("kibo:drops:7")).thenReturn("{this is not json");

        assertThat(cache.findDrop(7L)).isEmpty();

        verify(redis).delete("kibo:drops:7");
        when(values.get("kibo:drops:8")).thenReturn(null);
        cache.findDrop(8L);
        verify(values).get("kibo:drops:8"); // Redis still in use
    }

    @Test
    void whileBypassedNothingAtAllIsSentToRedis() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        cache.findAll();
        org.mockito.Mockito.clearInvocations(redis, values);

        cache.putDrop(SNAPSHOT);
        cache.evict(1L);

        verifyNoInteractions(values);
        verify(redis, never()).delete(anyCollection());
    }

    /** A clock the test can move. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
