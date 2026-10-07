# Redis caching of drop reads

Redis is a **cache and nothing else**. MySQL is the source of truth for every inventory fact, and no
decision (hold, confirm, cancel, expire) ever reads Redis. If Redis disappears, the service keeps working
and returns MySQL's answers. Related: constitution principles III and V, spec FR-027 / FR-028 / SC-005 /
SC-008, `specs/001-drop-reservation-service/research.md` §7.

## What is cached

| Endpoint | Redis key | Value | TTL |
|---|---|---|---|
| `GET /api/v1/drops` | `kibo:drops:all` | JSON array of drop snapshots | `kibo.cache.drop-ttl` (default 3 s) |
| `GET /api/v1/drops/{dropId}` | `kibo:drops:<id>` | JSON drop snapshot | same |

* A **snapshot** holds `id, name, description, totalQuantity, availableQuantity, maxPerHold, startsAt`.
  It is a plain read model, never a JPA entity, and is serialized with typed JSON (no class names in Redis).
* `availabilityStatus` (`UPCOMING` / `OPEN` / `SOLD_OUT`) is **not** cached. It is computed on every read
  from the snapshot and the current clock, so a drop still opens exactly on time even when its cached
  snapshot predates the release.
* **Never cached:** holds (any operation), unknown drops (a 404 is never stored), and anything used to decide
  whether a hold may be granted.

## Read path (cache-aside)

1. Try Redis. A hit is returned as is (no database connection is even opened).
2. On a miss, or on **any** Redis problem, read MySQL in a short read-only transaction.
3. Offer the result to Redis with the TTL. If Redis refuses, nobody notices.

## Keeping it fresh: eviction after commit

Every code path that actually changes a hold raises an in-process `HoldLifecycleEvent` *inside* its
transaction (created, confirmed, cancelled, expired). `DropCacheEvictionListener` handles it with
`@TransactionalEventListener(AFTER_COMMIT)`:

| Change | Availability changes? | Cache evicted? |
|---|---|---|
| Hold created | yes (units taken) | yes: the drop key and the list key |
| Hold cancelled | yes (units returned) | yes |
| Hold expired (sweep or settle-on-contact) | yes (units returned) | yes |
| Hold confirmed | no (units stay consumed) | no |
| Replay, repeat, rejected request, rolled-back transaction | no change was committed | no (no event is raised) |

**Why after commit, never before:** if the entry were deleted before the commit, a concurrent reader could
immediately re-read MySQL (still the old value) and re-cache it, and the stale value would then outlive the
change. After commit, the next reader sees the new value. Because the callback only runs for committed work,
a rollback evicts nothing.

Eviction deletes the drop's key and the list key together. Other drops' keys are left alone. With several
application instances this just works: Redis is shared, so one instance's eviction clears it for all.

## Staleness: exactly what can be stale, and for how long

**Hard bound: an entry is never served for longer than the TTL (3 s by default, below the 5 s of SC-008).**
Eviction makes the normal case far better than that, but the bound is what the design guarantees.

| Situation | What a customer can see | How long |
|---|---|---|
| Normal change | Old value for the milliseconds between the commit and the eviction | ms |
| A reader loaded MySQL just *before* a commit and wrote to Redis just *after* the eviction (benign race) | Pre-change availability | at most the TTL |
| The eviction was lost (Redis blip, timeout) | Pre-change availability | at most the TTL |
| Redis down or slow | Nothing stale: reads come straight from MySQL | n/a |
| Redis restarted / flushed | Nothing stale: the cache is simply empty | n/a |
| A drop reaches its release time | `UPCOMING` flips to `OPEN` on time (status is computed on read) | none |

What that means for people:

* A customer may see "3 available" and then get `409 INSUFFICIENT_INVENTORY` (the response includes the
  real `availableQuantity`), or see `SOLD_OUT` for a moment after units were returned by a cancel or expiry.
  This is accepted and documented, because **the displayed number is informational**.
* It can never cause an oversell. The hold decision is one atomic conditional `UPDATE` in MySQL and does
  not look at Redis (Constitution II, IV, V). A test proves it: with a stale cache saying 5 and MySQL saying
  0, the hold is refused.
* Units returned by an expiry become visible after the expiry sweep (every 2 s) plus, in the worst case of a
  lost eviction, one TTL.

## When Redis is unavailable (fail open)

* Every Redis call is guarded. A failure (down, timeout, garbage value) is a **miss** for reads and a
  **skipped write or eviction**, never an error for the caller. Timeouts are short (connect 500 ms, command
  200 ms) so a slow Redis cannot make the service slow.
* After the first failure the cache is **bypassed for `max(5 s, TTL)`**: requests go straight to MySQL
  without touching Redis, so an outage costs about one timeout in total instead of one per request, and it
  produces one WARN log line, not one per request. The window is at least the TTL on purpose: every entry
  written before the failure has expired by the time Redis is used again, so a skipped eviction can never
  extend staleness beyond the TTL. Afterwards the cache resumes by itself.
* An unreadable entry (corrupt or from another version) is treated as a miss and deleted.
* Readiness (`/actuator/health/readiness`) depends on MySQL only. Redis shows up under `/actuator/health`
  as a component but never marks the service unready.
* The bypass flag is plain per-instance JVM state, used purely to save time. Correctness never depends on it.

## Why Redis is not authoritative

A cache can lag, be flushed, restart empty or be unreachable. Inventory facts must not depend on any of that,
so: reservations are decided by MySQL alone, the cache is optional (`SC-005`: all concurrency and lifecycle
checks pass with it down), and its contents are always disposable and always rebuildable from MySQL.

## Known limitations / possible later work

* **Stampede:** when an entry expires or is evicted, concurrent readers each load MySQL once until one
  re-populates it. Reads are cheap indexed queries, so this was not worth locking or request coalescing.
* **No negative caching** of unknown drop ids, deliberately (it would only help a flood of invalid ids).
* **Per-instance bypass:** each instance detects a Redis failure on its own. A shared circuit breaker was
  judged unnecessary.
* **Thread interrupts** (only at shutdown) are treated like a Redis failure and start a bypass window.
* Eviction runs synchronously after commit. With a *hung* Redis that can add up to one command timeout to
  the one request that notices; later requests skip Redis during the bypass window. Moving eviction to an
  async executor is a possible refinement.

## Configuration

| Setting | Default | Meaning |
|---|---|---|
| `kibo.cache.drop-ttl` | `PT3S` | upper bound on staleness (keep it below 5 s) |
| `spring.data.redis.host/port/password` | env `REDIS_HOST/PORT/PASSWORD` | connection (never hard-coded) |
| `spring.data.redis.connect-timeout` / `timeout` | `500ms` / `200ms` | fail fast |

## How it is tested

| Test | Proves |
|---|---|
| `DropCacheTest` (unit) | keys, TTL, JSON shape (no status), failure becomes a miss, bypass window and its minimum length, unreadable entry |
| `DropCacheEvictionListenerTest` (unit) | evicts on create / cancel / expire, not on confirm; runs only AFTER_COMMIT |
| `DropQueryServiceTest` (unit) | cache-aside order, hit skips MySQL and the transaction, 404 not cached |
| `HoldService*Test`, `HoldExpirationServiceTest` (unit) | the right event is raised only on a real change, and no event for replays, rejections or failed returns |
| `CacheIsNotUsedForDecisionsTest` (unit) | the hold path and the repositories cannot even reference the cache or Redis |
| `DropCacheIT` (real Redis) | JSON + TTL in Redis, served from cache until expiry, evicted by create / cancel / expire, kept on confirm / replay / rejection, status computed after cache read, hold decision ignores a stale cache, 200 concurrent holds + readers: no oversell and the cache converges within the TTL |
| `DropCacheOutageIT` (real Redis, paused) | reads, holds and cancels keep working with a hung Redis without meaningful delay, and caching resumes after it returns |
| every other `*IT` | run with Redis pointed at a closed port, so all concurrency, lifecycle and race tests also pass without Redis |
