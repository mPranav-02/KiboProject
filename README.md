# KIBO Limited Drop Reservation Service

The backend for a limited-availability "drop" platform. A customer places a **hold** on units of a drop,
then **confirms** it, **cancels** it, or lets it **expire** so the units go back into the pool. The one rule
that matters most: **never sell more units than exist**, even when hundreds of requests hit the same few units
at the same moment, across several app instances, with retries and partial failures.

**Stack:** Java 21 · Spring Boot 3.5 · MySQL 8.4 (source of truth) · Redis 7 (read cache only) ·
RabbitMQ 4 (lifecycle events only) · Flyway · JUnit 5 + Mockito · Testcontainers

---

## Quick start

```bash
docker compose up --build        # or: docker-compose up --build
```

That's all. No `.env` is needed: compose reads the committed **local-only placeholder** values in
`.env.example`. To change any value, copy it to `.env` (gitignored); values in `.env` win.

- API: http://localhost:8080/api/v1
- Readiness: `curl localhost:8080/actuator/health/readiness`
- RabbitMQ UI: http://localhost:15672 (default `kibo_local` / `change-me-local-only`)

On startup the service seeds four drops: a 50-unit sneaker release, a 5-seat chef's table, a 1-unit concert
ticket (the "last unit" race) and an appointment drop that opens 10 minutes after startup.

### Try the core flow

```bash
curl -s localhost:8080/api/v1/drops

# Place a hold → 201 ACTIVE, expires in 5 minutes
curl -si -X POST localhost:8080/api/v1/drops/1/holds \
  -H 'Content-Type: application/json' -H 'X-Customer-Id: alice' -H 'Idempotency-Key: k1' \
  -d '{"quantity":2}'

# Same request again → 200, same hold, nothing consumed twice
# Confirm (use the id from the response)
curl -s -X POST localhost:8080/api/v1/holds/<holdId>/confirm -H 'X-Customer-Id: alice'
```

To watch expiry quickly, put `KIBO_HOLD_DURATION=PT20S` in `.env` and restart.
More scenarios (edge cases, failure drills): [quickstart.md](specs/001-drop-reservation-service/quickstart.md).

### Run the tests

```bash
./mvnw test      # unit + controller tests: JUnit 5 + Mockito, NO Docker/MySQL/Redis/RabbitMQ needed
./mvnw verify    # + integration tests against real MySQL/Redis/RabbitMQ via Testcontainers (Docker needed)
./mvnw verify -Dkibo.it.runs=100 -Dkibo.it.raceRuns=1000   # heavier concurrency repetitions
```

---

## API

All paths are under `/api/v1`. Hold endpoints need an `X-Customer-Id` header (stand-in for real auth).

| Method & path | Purpose | Success |
|---|---|---|
| `GET /drops` | List drops with live availability | 200 |
| `GET /drops/{dropId}` | One drop | 200 |
| `POST /drops/{dropId}/holds` | Place a hold. Body `{"quantity": n}`, header `Idempotency-Key` required | 201 (new) / 200 (replay) |
| `GET /holds/{holdId}` | View your hold | 200 |
| `POST /holds/{holdId}/confirm` | Claim the units | 200 |
| `POST /holds/{holdId}/cancel` | Release the units early | 200 |

Errors are RFC 7807 `application/problem+json` with a machine-readable `code` (plus context such as
`availableQuantity` or `currentStatus`); stack traces are never returned.

| Status | Codes |
|---|---|
| 400 | `VALIDATION_ERROR` (with `errors[{field,message}]`) |
| 404 | `DROP_NOT_FOUND`, `HOLD_NOT_FOUND` (also for another customer's hold, so ids can't be probed) |
| 409 | `INSUFFICIENT_INVENTORY`, `DROP_NOT_RELEASED`, `HOLD_EXPIRED`, `INVALID_STATE_TRANSITION`, `IDEMPOTENCY_KEY_CONFLICT` |
| 503 | `SERVICE_UNAVAILABLE`: database unreachable, lost during commit, lock conflict or timeout; nothing changed, safe to retry |

Full contract: [openapi.yaml](specs/001-drop-reservation-service/contracts/openapi.yaml).

---

## Design decisions and trade-offs

Diagrams: [docs/HLD.md](docs/HLD.md). Each decision with the alternatives rejected:
[research.md](specs/001-drop-reservation-service/research.md).

### 1. No overselling: one atomic conditional UPDATE in MySQL

```sql
UPDATE drops SET available_quantity = available_quantity - :qty
 WHERE id = :dropId AND available_quantity >= :qty AND starts_at <= :now
```

InnoDB serializes concurrent updates of the drop row and re-checks the `WHERE` against the latest committed
value, so a request that would go negative affects 0 rows and gets `409 INSUFFICIENT_INVENTORY`. The decrement
and the INSERT of the ACTIVE hold commit or roll back together. A `CHECK (available_quantity BETWEEN 0 AND
total_quantity)` constraint is a last line of defence.

- **Rejected:** `synchronized`/JVM locks (useless with a second instance); Redis `DECR` as the gate (Redis
  loss or failover could oversell); optimistic `@Version` (retry storms on a hot drop); `SELECT … FOR UPDATE`
  (correct, but two round trips and a longer lock for no gain).
- **Trade-off:** every hold on one drop queues on one row. That's fine at this scale; bucketed counters
  or a waiting room are the scaling path.

### 2. State transitions: guarded updates make exactly one winner

The hold lifecycle is `ACTIVE → CONFIRMED | CANCELLED | EXPIRED`, and all three are final. The transition
table lives only in `HoldStatus`, and a test fails if any other code hard-codes it. Every transition is one
guarded UPDATE:

```sql
UPDATE holds SET status = 'CANCELLED' ...
 WHERE id = ? AND customer_id = ? AND status IN ('ACTIVE') AND expires_at > :now
```

When confirm, cancel and the expiry job race on the same hold, exactly one statement affects a row. Only
that winner returns the units, in the **same transaction**, so units are returned exactly once: never lost,
never doubled. A hold can't be confirmed after `expires_at` even if the sweeper hasn't processed it yet.
Repeating a confirm or cancel that already happened is an idempotent 200.

- **Rejected:** load the entity, check it, save it (JPA dirty checking). Cancel and expire could both "win"
  that read-then-write race and return the units twice.

### 3. Expiration: a scheduled sweep that's safe on every instance

Every 2 s (configurable), each instance selects overdue ACTIVE holds and expires each one in its own
transaction using the guarded UPDATE above. Overlapping sweeps on many instances are harmless, so there's
no leader election or ShedLock. Units are back within about 2 s of expiry (the spec's bound is 10 s), and
`GET` reports an overdue hold as `EXPIRED` straight away.

- **Rejected:** RabbitMQ delayed messages or Redis keyspace notifications (they make correctness depend on
  infrastructure that may lose messages); lazy-only expiry (unvisited holds would lock units forever).

### 4. Idempotent hold placement

`Idempotency-Key` is required and stored with a `UNIQUE (customer_id, request_key)` constraint (binary
collation, so `K1` ≠ `k1`). A retried request returns the original hold instead of taking more units, even
when the duplicates arrive concurrently: the loser hits the unique key, its whole transaction (including the
decrement) rolls back, and it replays the winner. Reusing a key with a different body returns `409`.

### 5. Redis is only a cache

Redis caches the two drop read endpoints (cache-aside, 3 s TTL, evicted after each commit that changes
availability). It is **never** read when deciding a hold. If Redis is down, slow or flushed, reads fall back
to MySQL with short timeouts, the cache is bypassed for a few seconds, and results stay correct.

- **Trade-off:** a drop list can be up to 3 s stale, so a customer may see "available" and then get
  `INSUFFICIENT_INVENTORY`. That's acceptable because the hold decision is always authoritative.
  Details: [docs/caching-redis.md](docs/caching-redis.md).

### 6. RabbitMQ carries after-the-fact events

`hold.created|confirmed|cancelled|expired` go to topic exchange `kibo.holds` (demo queue `kibo.holds.audit`,
drained by a logging consumer). Events are published **after commit**, asynchronously, with publisher
confirms, so a dead or hanging broker never slows or fails a request, and no event is ever sent for a change
that rolled back.

- **Trade-off:** delivery is at-most-once. A crash between commit and publish, or a broker outage, loses
  that event. A transactional outbox fixes this (see below). Details:
  [docs/messaging-rabbitmq.md](docs/messaging-rabbitmq.md).

### 7. Other choices

- **Layering:** controllers only map HTTP; rules live in `application`/`domain`; repositories, cache and
  messaging are adapters. That's why the business logic is unit-testable with mocks.
- **READ COMMITTED isolation:** correctness comes from the conditional updates, not the isolation level,
  and this avoids InnoDB gap locks between the expiry scan and inserts.
- **Configuration:** `application.yml` has **no** defaults for hosts or credentials; a missing variable
  fails startup, and so does a non-positive hold duration. `docker-compose.yml` holds no credentials
  either; it reads the local-only placeholders in `.env.example`, overridable by `.env`.
- **Readiness** depends on MySQL only, so a Redis or RabbitMQ outage doesn't take the service out of rotation.
- **Time:** an injected UTC `Clock` makes expiry tests deterministic.
- **Money:** none is modelled (drops have no price), so there was no `BigDecimal` to use.

---

## How correctness is tested

The unit suite (`*Test`) runs with no infrastructure. The integration suite (`*IT`) runs against real
MySQL 8.4, because H2's locking can't prove anything about InnoDB. Every integration test checks the
invariant `total − available = Σ quantity(ACTIVE + CONFIRMED)` and `available ≥ 0`.

| Test | Proves |
|---|---|
| `ConcurrentReservationIT` | 200 parallel requests for 50 units → exactly 50 holds, never oversold (repeated) |
| `HoldRaceIT`, `ConfirmCancelRaceIT` | confirm/cancel vs expire races: one final state, units returned once |
| `IdempotentHoldIT` | 10 concurrent same-key requests → one hold, one decrement |
| `ExpirationIT`, `HoldExpirationJobIT` | expiry within bound, concurrent sweepers safe, no confirm after expiry |
| `TransactionBoundaryIT` | a failure mid-transaction rolls back both the hold and the inventory change |
| `ConcurrentReservationBrokersDownIT`, `HoldRaceBrokersDownIT` | the same concurrency and race suites, events on, Redis and RabbitMQ unreachable |
| `DropCacheOutageIT`, `RabbitOutageIT`, `RabbitBrokerHangIT` | correct results with Redis/RabbitMQ down or hanging |
| `CaseSensitivityIT` | `bob`/`Bob` and `k1`/`K1` never collide |

---

## Known limitations

- A lock conflict or deadlock returns `503` (the transaction rolled back, so it's safe to retry) rather
  than being retried server-side.
- Events are at-most-once (see §6).
- `X-Customer-Id` is a trusted header, not authentication.
- Startup seeding is count-then-insert, so it assumes one instance starts against an empty database.

## What I'd build next

1. **Transactional outbox** for at-least-once events, with consumers de-duplicating by `eventId`.
2. **Bounded server-side retry** on deadlock/lock-timeout before answering 503.
3. **Hot-drop scaling:** bucketed inventory rows or a queue-based "waiting room" in front of hold creation.
4. **Real auth** (OAuth2/JWT) instead of `X-Customer-Id`, plus per-customer purchase limits and rate limiting.
5. **Metrics** (Micrometer/Prometheus): holds granted/rejected, expiry lag, cache hit ratio, publish failures.
6. `SKIP LOCKED` batched expiry for very high volumes; archiving of final-state holds.
7. Admin API for creating drops; pagination of the drop list; upgrade to Spring Boot 4.

---

## Repository map

| Path | Contents |
|---|---|
| `src/main/java/com/kibo/reservation` | `api`, `application`, `domain`, `repository`, `cache`, `messaging`, `config`, `exception` |
| `src/main/resources/db/migration` | Flyway schema |
| `docs/` | HLD diagrams, Redis and RabbitMQ design notes |
| `specs/001-drop-reservation-service/` | Spec, plan, decisions (`research.md`), data model, API/event contracts, tasks |
| `.specify/memory/constitution.md` | The 17 project principles every change was checked against |
| [AI-USAGE.md](AI-USAGE.md) | How AI tools were used and steered, accepted vs rejected suggestions, validation |
