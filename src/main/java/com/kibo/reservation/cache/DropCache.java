package com.kibo.reservation.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kibo.reservation.application.DropSnapshot;
import com.kibo.reservation.config.KiboProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis read cache for drops. <b>A cache and nothing more</b> (Constitution V, FR-027):
 * <ul>
 *   <li>Only the two drop read endpoints use it. Placing, confirming, cancelling and expiring holds never
 *       read it; MySQL's atomic conditional update decides inventory.</li>
 *   <li>Cache-aside with a short TTL ({@code kibo.cache.drop-ttl}, default 3 s), so any entry, however it
 *       got stale, is gone within the TTL. Entries are JSON snapshots, never entities.</li>
 *   <li><b>Fail open.</b> Every Redis problem (down, slow, timeout, garbage value) is treated as a miss or a
 *       skipped write, never as an error for the caller. After a failure the cache is bypassed for
 *       {@code max(5 s, TTL)}, so an outage costs one slow call, not one per request. Because that is at
 *       least the TTL, no entry written before the failure can still be served when the bypass ends, so a
 *       skipped eviction cannot extend staleness beyond the TTL.</li>
 * </ul>
 * The bypass flag is plain JVM state used only to save time; correctness never depends on it.
 *
 * <p>Keys: {@code kibo:drops:all} (the list) and {@code kibo:drops:<id>} (one drop). The derived
 * availability status (UPCOMING/OPEN/SOLD_OUT) is NOT cached; it is computed from the snapshot and the
 * current clock on every read.
 */
@Component
public class DropCache {

    private static final Logger log = LoggerFactory.getLogger(DropCache.class);

    static final String ALL_KEY = "kibo:drops:all";
    static final String DROP_KEY_PREFIX = "kibo:drops:";
    private static final Duration MIN_BYPASS = Duration.ofSeconds(5);
    private static final TypeReference<List<DropSnapshot>> SNAPSHOT_LIST = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration ttl;
    private final Duration bypassAfterFailure;
    private volatile Instant bypassUntil = Instant.MIN;

    public DropCache(StringRedisTemplate redis, ObjectMapper mapper, KiboProperties properties, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
        this.ttl = properties.cache().dropTtl();
        this.bypassAfterFailure = ttl.compareTo(MIN_BYPASS) > 0 ? ttl : MIN_BYPASS;
    }

    public Optional<DropSnapshot> findDrop(long dropId) {
        String key = dropKey(dropId);
        return read(key, json -> mapper.readValue(json, DropSnapshot.class));
    }

    public Optional<List<DropSnapshot>> findAll() {
        return read(ALL_KEY, json -> mapper.readValue(json, SNAPSHOT_LIST));
    }

    public void putDrop(DropSnapshot drop) {
        write(dropKey(drop.id()), drop);
    }

    public void putAll(List<DropSnapshot> drops) {
        write(ALL_KEY, drops);
    }

    /**
     * Drops the cached copy of this drop and of the list. Called after the transaction that changed the drop's
     * availability has committed. Best effort: if it fails, the TTL still bounds the staleness.
     */
    public void evict(long dropId) {
        guarded("evict", () -> redis.delete(List.of(dropKey(dropId), ALL_KEY)));
    }

    // ------------------------------------------------------------------ internals

    private interface JsonReader<T> {
        T read(String json) throws JsonProcessingException;
    }

    private <T> Optional<T> read(String key, JsonReader<T> reader) {
        String json = guarded("get", () -> redis.opsForValue().get(key));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(reader.read(json));
        } catch (JsonProcessingException unreadable) {
            // Not a Redis outage: a value we cannot read (e.g. written by an older version). Treat as a miss
            // and remove it so the next read repopulates it.
            log.warn("Ignoring unreadable cache entry {}: {}", key, unreadable.getOriginalMessage());
            guarded("evict", () -> redis.delete(key));
            return Optional.empty();
        }
    }

    private void write(String key, Object value) {
        if (isBypassed()) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(value);
            guarded("put", () -> {
                redis.opsForValue().set(key, json, ttl);
                return null;
            });
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize cache entry {}: {}", key, e.getOriginalMessage());
        }
    }

    /** Runs one Redis call; any failure becomes "no result" and starts the bypass window. */
    private <T> T guarded(String operation, Supplier<T> call) {
        if (isBypassed()) {
            return null;
        }
        try {
            return call.get();
        } catch (RuntimeException e) {
            // Many requests can be in flight when Redis fails. Only the first to notice starts the window and
            // logs at WARN; the rest just note it at DEBUG, so an outage is one log line, not one per request.
            boolean firstToNotice = !isBypassed();
            bypassUntil = clock.instant().plus(bypassAfterFailure);
            if (firstToNotice) {
                log.atWarn()
                        .addKeyValue("operation", operation)
                        .addKeyValue("bypassSeconds", bypassAfterFailure.toSeconds())
                        .log("Redis cache unavailable, serving from MySQL for now: {}", e.getMessage());
            } else {
                log.debug("Redis {} failed during the bypass window: {}", operation, e.getMessage());
            }
            return null;
        }
    }

    private boolean isBypassed() {
        return clock.instant().isBefore(bypassUntil);
    }

    private static String dropKey(long dropId) {
        return DROP_KEY_PREFIX + dropId;
    }
}
