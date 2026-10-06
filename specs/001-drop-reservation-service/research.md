# Research & Decisions: Limited Drop Reservation Service

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-10-06

Each section records **Decision / Rationale / Alternatives considered**. The Technical Context has no
remaining NEEDS CLARIFICATION items. These sections are the source material for the `docs/` files and ADRs
produced during implementation (Constitution XVII).

---

## §0 Platform versions

- **Decision**:
  - Java 21 LTS.
  - Spring Boot **3.5.x**, using the latest patch at implementation time.
  - MySQL **8.4 LTS**, Redis **7.x**, RabbitMQ **3.13+/4.x** (management image).
  - Maven 3.9+.
- **Rationale**:
  - The brief asks for Spring Boot 3.2+, and 3.5 is the newest 3.x line.
  - 3.4+ adds built-in structured logging, which we use for FR-031.
  - MySQL 8.0.16+ enforces `CHECK` constraints, which we rely on as defense in depth.
- **Note / risk**: Public EOL trackers report that Spring Boot 3.5 open-source support ended in June
  2026. That is acceptable for a take-home; upgrading to Spring Boot 4.x is listed in Future
  Improvements (§14).
- **Alternatives considered**:
  - Spring Boot 4.x: newest, but it's a major upgrade (Jackson 3, modularized starters) and outside
    the requested "3.2+" intent.
  - Spring Boot 3.2: older and also out of support.

## §1 Architecture: layered modular monolith

- **Decision**: One deployable Maven module with packages per layer:
  - `api` (+`dto`), `application`, `domain`, `repository`, `cache`, `messaging`, `config`, `exception`.
  - Dependencies point inward: api → application → domain. The repository, cache and messaging layers are
    adapters used by application.
  - Domain entities (`Drop`, `Hold`) carry JPA annotations. DTOs at the API edge mean entities are never
    exposed.
- **Rationale**:
  - Meets Constitution X (rules in application/domain, testable with mocks) and XV (simplicity).
  - A separate persistence model plus mappers would double the class count for no correctness gain in
    two days.
- **Alternatives considered**:
  - Hexagonal architecture with pure domain plus persistence entities: cleaner, but more mapping code.
    Rejected for scope.
  - Microservices (inventory vs. holds): introduces distributed transactions. Rejected (XV, I).

## §2 Concurrency strategy: placing a hold (no oversell)

- **Decision**: In one transaction:
  1. **Idempotency lookup**: `SELECT` the hold by `(customer_id, request_key)`. If found, replay it (see §5).
  2. **Atomic conditional decrement**:
     ```sql
     UPDATE drops
        SET available_quantity = available_quantity - :qty, updated_at = :now
      WHERE id = :dropId AND available_quantity >= :qty AND starts_at <= :now;
     ```
     This is implemented as a Spring Data `@Modifying(clearAutomatically = true, flushAutomatically = true)`
     JPQL update that returns the affected-row count.
  3. If **1 row** was affected, `INSERT` the hold (ACTIVE, `expires_at = now + holdDuration`) and flush
     inside the transaction.
  4. If **0 rows** were affected, classify the failure:
     - re-check the request key (a concurrent duplicate may have just committed; replay it);
     - otherwise load the drop and return `DROP_NOT_FOUND`, `DROP_NOT_RELEASED` or `INSUFFICIENT_INVENTORY`.
- **Why it's safe**:
  - InnoDB takes an exclusive row lock on the drop row for the `UPDATE`. Concurrent decrements are
    serialized, and each one re-evaluates `available_quantity >= :qty` against the latest committed value.
    A request that would go negative affects 0 rows.
  - The decrement and the hold insert commit or roll back together.
  - Defense in depth: `CHECK (available_quantity BETWEEN 0 AND total_quantity)` makes any bug fail loudly
    instead of overselling.
- **Rationale**: The brief asks for exactly this. It's the simplest correct approach: one statement, no
  read-modify-write, no retries in the common path, and it's naturally safe across instances.
- **Alternatives considered**:
  - `SELECT ... FOR UPDATE` then check and update: correct, but two round trips and a longer lock hold for
    no benefit. Kept as a documented equivalent.
  - Optimistic locking (`@Version`) on the drop: a hot drop causes retry storms under 200 concurrent
    requests (SC-007 risk).
  - A Redis `DECR` or Lua script as the gate: violates Constitution III and V. Redis loss or failover could
    oversell.
  - A JVM `synchronized` block or `ReentrantLock`: violates IX and breaks with more than one instance.
  - Deriving availability as `total - SUM(active + confirmed)` on every request: needs a lock on the whole
    hold set, is slower, and is harder to keep correct. Instead, the stored counter is verified against
    this sum in tests.
- **Known limit**: All holds on one drop serialize on one row. That's fine at take-home scale.
  Sharded or bucketed counters are future work (§14).

## §3 Hold transitions: confirm, cancel, expire (exactly-once return)

- **Decision**: Every transition is a **guarded atomic update** keyed on the expected current state:
  ```sql
  -- confirm (inventory stays consumed)
  UPDATE holds SET status='CONFIRMED', resolved_at=:now, updated_at=:now
   WHERE id=:id AND customer_id=:cust AND status='ACTIVE' AND expires_at > :now;

  -- cancel
  UPDATE holds SET status='CANCELLED', resolved_at=:now, updated_at=:now
   WHERE id=:id AND customer_id=:cust AND status='ACTIVE' AND expires_at > :now;

  -- expire
  UPDATE holds SET status='EXPIRED', resolved_at=:now, updated_at=:now
   WHERE id=:id AND status='ACTIVE' AND expires_at <= :now;
  ```
  - **Cancel and expire**: only if exactly **1 row** changed, the same transaction runs the guarded
    increment:
    ```sql
    UPDATE drops SET available_quantity = available_quantity + :qty, updated_at = :now
     WHERE id = :dropId AND available_quantity + :qty <= total_quantity;
    ```
    If that affects 0 rows, the invariant is broken. The service throws, the transaction rolls back, and an
    ERROR is logged. This should be impossible, and it guards against double release.
  - **0 rows changed**: load the hold and classify the outcome:
    - not found, or the customer doesn't match: `HOLD_NOT_FOUND` (404);
    - already in the requested state: idempotent success (FR-021);
    - expired, or ACTIVE but past `expires_at`: `HOLD_EXPIRED` (409). The service also runs the guarded
      expire transition for that hold so it settles immediately;
    - any other final state: `INVALID_STATE_TRANSITION` (409), including the current status.
  - Domain: `HoldStatus` holds an explicit transition table (`ACTIVE → {CONFIRMED, CANCELLED, EXPIRED}`;
    final states have none). The service checks it before issuing SQL (Constitution VI). The SQL guard
    makes it race-proof.
- **Why exactly-once holds**: For any hold, at most one statement can ever match `status='ACTIVE'`. The
  others see the committed final state and affect 0 rows. Inventory is returned only on the 1-row path, in
  the same transaction, so it is never returned zero times on success and never twice.
- **Alternatives considered**:
  - Load the entity, check its status, set the new status and save, using JPA dirty checking: the
    read-then-write race lets cancel and expire both "win". Rejected.
  - `@Version` optimistic locking on Hold: also correct, but it needs exception-driven flow and retries.
    The guarded update is simpler and explicit.

## §4 Transaction boundaries, isolation and retries

- **Decision**:

  | Operation | Transaction contents | After commit |
  |-----------|---------------------|--------------|
  | Place hold | key lookup → conditional decrement → insert hold | evict drop cache; publish `HOLD_CREATED` |
  | Confirm | guarded update of hold | publish `HOLD_CONFIRMED` (availability unchanged, so no eviction) |
  | Cancel | guarded update of hold → guarded increment of drop | evict drop cache; publish `HOLD_CANCELLED` |
  | Expire (per hold) | guarded update of hold → guarded increment of drop | evict drop cache; publish `HOLD_EXPIRED` |
  | Reads | `readOnly` transaction or cache | — |

  - `@Transactional` sits on application-service methods only, never on controllers.
  - Side effects (cache eviction, event publishing) run from `@TransactionalEventListener(phase = AFTER_COMMIT)`.
    They never run for rolled-back work.
  - **Isolation `READ COMMITTED`**, set on the Hikari datasource. Correctness relies on the conditional
    updates, not on the isolation level. `READ COMMITTED` avoids InnoDB gap and next-key locks from the
    expiry range scan blocking hold inserts, and the 0-rows re-check of the request key sees freshly
    committed rows.
  - **Lock order**: hold placement touches drop then hold insert; cancel and expire touch hold then drop.
    Deadlocks are unlikely but possible. Failures with `CannotAcquireLockException` or
    `PessimisticLockingFailureException` are **retried up to 3 times with jitter**. The retry wraps the
    whole transaction, from outside the transactional proxy.
  - **Duplicate request key race**: a unique-key violation on insert rolls back the whole transaction, so
    the decrement is undone. The caller, outside the transaction, catches it and replays the existing hold.
- **Alternatives considered**:
  - `REPEATABLE READ` (MySQL default): works, but adds gap-lock contention and deadlock surface for no
    benefit here.
  - Publishing events inside the transaction: could announce something that later rolls back. Rejected.

## §5 Idempotent hold placement (request key)

- **Decision**:
  - An `Idempotency-Key` header is **required** on `POST /drops/{id}/holds` (1–64 chars). It's stored as
    `holds.request_key` with `UNIQUE (customer_id, request_key)`.
  - Replay with the same drop and quantity returns the original hold (current state) with **200 OK**; a
    first create returns **201**.
  - The same key with a different drop or quantity returns **409 `IDEMPOTENCY_KEY_CONFLICT`**.
  - Keys live as long as the hold row.
- **Rationale**: Covers FR-009 and SC-003, including truly concurrent duplicates (via the unique
  constraint, §4), without a separate idempotency store.
- **Alternatives considered**:
  - A Redis idempotency store: not authoritative, so it could double-allocate after Redis loss. Rejected (V).
  - An optional key: clarified as required (spec Clarifications).

## §6 Expiration

- **Decision**: `HoldExpirationJob` runs `@Scheduled(fixedDelayString = "${kibo.expiration.interval:PT2S}")`.
  1. Query: `SELECT id FROM holds WHERE status='ACTIVE' AND expires_at <= :now ORDER BY expires_at LIMIT :batch`
     (default batch 200, no locks). It uses index `(status, expires_at)`.
  2. For each id, `HoldExpirationService.expire(id, now)` runs the guarded expire plus release (§3) in **its
     own transaction**.
  3. Loop while a full batch was returned.

  - **Safe on N instances and on re-runs**: competing sweepers, or a cancel or confirm, race on the same
    guarded `UPDATE`. One wins, the others affect 0 rows and do nothing. No ShedLock or leader election.
  - **Bound**: 2s interval plus processing comfortably meets FR-018 (≤10s). It's configurable.
  - **Reads**: `GET /holds/{id}` reports `EXPIRED` for an ACTIVE hold past `expires_at` (FR-019). The read
    path stays read-only; confirm and cancel settle such holds on contact (§3).
  - **Restart**: overdue holds are picked up on the first sweep after startup.
- **Alternatives considered**:
  - RabbitMQ delayed or TTL dead-letter messages per hold: makes expiry depend on messaging. Rejected
    (FR-027, V).
  - Redis keyspace notifications: lossy, and not authoritative. Rejected.
  - `SELECT ... FOR UPDATE SKIP LOCKED` batches: correct, but unnecessary because guarded updates are
    already idempotent. Kept as a scaling option.
  - Lazy-only expiry (only when someone touches the hold): units could stay stuck forever. Rejected.

## §7 Redis caching (trade-offs, invalidation, consistency)

- **Decision**:
  - Spring Cache with Redis, **cache-aside**, for the two read endpoints:
    - `drops:all` (list);
    - `drops:{id}` (single).
  - Cached values are **DTO snapshots** (JSON), not entities.
  - **TTL 3s**, configurable, so staleness is bounded below SC-008's 5s.
  - **Eviction after commit** on every inventory change (place, cancel, expire), from an AFTER_COMMIT
    listener.
  - `availabilityStatus` (UPCOMING/OPEN/SOLD_OUT) is **computed after reading the cache**, using the
    current clock and the cached `startsAt`/`availableQuantity`. A drop flips from UPCOMING to OPEN on time
    even if the cached entry predates release.
  - **Failure tolerance**: a custom `CacheErrorHandler` logs a WARN and falls through to MySQL. Lettuce
    timeouts are short (connect ~500ms, command ~200ms) so an outage doesn't stall requests.
  - **Never used for decisions**: the hold path reads MySQL only.
- **Consistency behavior**:
  - The cache can be stale by at most the TTL. That can happen when an eviction is lost (Redis blip) or when
    a reader repopulates with a pre-commit value just before the eviction.
  - Customers may briefly see a unit as available and then get `INSUFFICIENT_INVENTORY`. This is accepted
    and documented, because the hold decision is always authoritative.
- **Alternatives considered**:
  - Write-through or updating cached counters: more code, and a second "truth" that drifts.
  - Long TTL with eviction only: a lost eviction means long staleness.
  - No TTL: unbounded staleness.
  - Caching the entities: couples the cache to the persistence model.

## §8 RabbitMQ events (commit/publish trade-off)

- **Decision**:
  - The application layer raises a Spring `HoldLifecycleEvent` (in-process).
  - The `messaging` adapter listens with `@TransactionalEventListener(AFTER_COMMIT)` plus `@Async`, using a
    bounded executor.
  - It publishes JSON to topic exchange `kibo.holds` with routing keys
    `hold.created|confirmed|cancelled|expired`.
  - A durable demo queue `kibo.holds.audit` is bound to `hold.#` so events are visible in the management UI.
  - Publisher confirms are on. Failures are logged at WARN with the event payload and not retried.
- **Trade-off (documented)**: Publishing happens after the DB commit, so delivery is **at-most-once**:
  - a crash between commit and publish, a broker outage, or a full executor queue loses that event;
  - publishing before or inside the transaction would instead risk events for rolled-back changes;
  - core correctness never depends on events (FR-027).
- **Future improvement**: a **transactional outbox**:
  - write `outbox_events` rows in the same transaction;
  - a relay publishes them and marks them sent;
  - gives at-least-once delivery with consumer de-duplication by `eventId`.
- **Alternatives considered**:
  - Synchronous publish in the request: a broker outage would add connect-timeout latency to every request
    (SC-007).
  - Outbox now: more tables, a relay job and tests. Out of the two-day scope (XV).
  - Using messaging to drive expiry: rejected (§6).

## §9 API, identity and error model

- **Decision**:
  - **Endpoints**: the 6 endpoints in [contracts/openapi.yaml](./contracts/openapi.yaml).
  - **Identity**: `X-Customer-Id` header, required on all hold endpoints, 1–64 chars. A mismatch is
    reported as `HOLD_NOT_FOUND` (spec FR-022a).
  - **IDs**: drops use `BIGINT` (seeded). Holds use a random **UUID** (hard to guess).
  - **Status codes**:
    - 200: reads, confirm, cancel, idempotent replay;
    - 201 + `Location`: new hold;
    - 400 `VALIDATION_ERROR`;
    - 404 `DROP_NOT_FOUND` / `HOLD_NOT_FOUND`;
    - 409 `INSUFFICIENT_INVENTORY`, `DROP_NOT_RELEASED`, `HOLD_EXPIRED`, `INVALID_STATE_TRANSITION`,
      `IDEMPOTENCY_KEY_CONFLICT`;
    - 503 `SERVICE_UNAVAILABLE` (DB unreachable, or lock retries exhausted);
    - 500 `INTERNAL_ERROR`.
  - **Errors**: RFC 7807 `ProblemDetail` (`application/problem+json`) from one `@RestControllerAdvice`. It
    carries extension members `code`, `timestamp` and context such as `currentStatus` or
    `availableQuantity`. Stack traces are never returned.
- **Alternatives considered**:
  - 422 for business-rule failures: 409 better signals "conflicts with current resource state" and is
    consistent across the codes.
  - A custom error envelope: ProblemDetail is the built-in standard.

## §10 Testing strategy

- **Decision**: two suites.
  - **`mvn test`: unit and slice tests, no infrastructure (XIII).**
    - `HoldStatus` transition table: every pair, valid and invalid.
    - `HoldService` with mocked repositories and a fixed `Clock`:
      - success;
      - insufficient inventory, not released, drop not found;
      - idempotent replay, key conflict, and duplicate-key race fallback;
      - confirm and cancel classification (idempotent repeat, expired, invalid, wrong customer);
      - inventory return happens only on the 1-row path.
    - `HoldExpirationService`: releases only when the guarded update affected 1 row; a re-run is a no-op.
    - `@WebMvcTest` controllers: status codes, headers, validation, and ProblemDetail mapping for every
      `ErrorCode`.
    - Cache error handler, after-commit eviction, and the event publisher adapter (mocked `RabbitTemplate`;
      failures swallowed and logged).
  - **`mvn verify`: Testcontainers integration tests (`*IT`) against real MySQL 8.4.**
    - `ConcurrentReservationIT`: 200 threads behind a `CountDownLatch` start gate, 50 units. Asserts 50
      successes, 150 `INSUFFICIENT_INVENTORY`, available 0, and the invariant query. Repeated (default 20
      runs; `-Dkibo.it.runs=100` for SC-001). Also a mixed-quantity contention case.
    - `HoldRaceIT`: confirm vs expire and cancel vs expire, ×N (default 200, configurable to 1,000 for
      SC-002). Races are forced with two clocks either side of `expires_at` (simulating instance skew).
      Asserts exactly one final state and `available == total − confirmed`. Cancel vs confirm is covered too.
    - `IdempotentHoldIT`: 10 concurrent requests with the same key create exactly one hold and one
      decrement (SC-003).
    - `ExpirationIT`: overdue holds expire within the bound; 3 concurrent sweepers plus repeated sweeps
      return units once; confirm after expiry is rejected (SC-004).
    - `InfrastructureDownIT`: Redis and RabbitMQ pointed at closed ports. All flows still correct and drop
      reads served from MySQL (SC-005).
    - `EndToEndIT`: MySQL, Redis and RabbitMQ containers. REST happy path; cache evicted after a hold;
      event arrives on the audit queue.
  - **Invariant helper** used by every IT, per drop:
    `total − available == SUM(quantity WHERE status IN ('ACTIVE','CONFIRMED'))` and `available >= 0`.
- **Alternatives considered**:
  - H2 for integration tests: different locking semantics, so it can't prove no-oversell.
  - Only unit tests: concurrency guarantees live in the database and can't be mocked.

## §11 Configuration, Docker and seed data

- **Decision**:
  - `application.yml` reads all infrastructure settings from env vars with **no host or credential
    defaults**: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`,
    `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`.
  - Business settings have safe defaults: `kibo.hold.duration=PT5M`, `kibo.hold.default-max-per-hold=4`,
    `kibo.expiration.interval=PT2S`, `kibo.expiration.batch-size=200`, `kibo.cache.drop-ttl=PT3S`,
    `kibo.seed.enabled=true`.
  - Compose reads `.env`, which is gitignored. `.env.example` is committed with clearly marked local-only
    values.
  - `Dockerfile`: multi-stage build (maven:3.9-eclipse-temurin-21 → eclipse-temurin:21-jre), running as a
    non-root user.
  - `docker-compose.yml` services: `app`, `mysql` (8.4), `redis` (7), `rabbitmq` (management). Each has a
    healthcheck, and `app` uses `depends_on: condition: service_healthy`. Named volume for MySQL.
  - **Schema**: Flyway `V1__create_drops_and_holds.sql`. Hibernate `ddl-auto=validate`.
  - **Seeding**: an `ApplicationRunner` (`DataSeeder`) inserts ~4 drops **only if the drops table is
    empty**, with `startsAt` relative to now:
    - two open drops (e.g. 50 and 5 units);
    - one small "last unit" drop (1 unit);
    - one upcoming drop (+10 minutes).

    It can be disabled with `kibo.seed.enabled=false`, which ITs use.
- **Alternatives considered**:
  - A Flyway seed migration: static timestamps can't express "opens in 10 minutes".
  - `ddl-auto=update`: no CHECK constraints, and drift-prone.

## §12 Observability

- **Decision**:
  - Spring Boot structured logging (`logging.structured.format.console=ecs`).
  - The application layer logs every state change and rejection with `holdId`, `dropId`, `customerId`,
    `quantity` and `outcome`/`code` (FR-031).
  - Actuator exposes `health` only. The `readiness` group includes `db` (FR-032). Redis and RabbitMQ
    indicators show in the full health output but aren't in readiness, so their outage doesn't mark the
    service unready (FR-027).
- **Alternatives considered**: Micrometer metrics and tracing are out of scope per clarification;
  listed in §14.

## §13 Time

- **Decision**:
  - A single injected `java.time.Clock` (UTC). The current time is passed into every query as `:now`, so
    the service is deterministic in tests.
  - Columns are `DATETIME(6)` in UTC (`hibernate.jdbc.time_zone=UTC`).
  - Multiple instances are assumed to be NTP-synced. Skew only shifts the expiry boundary by milliseconds;
    the status guard still guarantees a single winner.
- **Alternatives considered**: DB `NOW(6)` inside SQL is one clock across instances, but it's harder to
  control in tests. It's noted as an option.

## §14 Future improvements (for docs/future-improvements.md)

- Transactional outbox plus relay for at-least-once events; consumer de-duplication by `eventId`.
- Hot-drop scaling: bucketed or sharded inventory rows, or a queue-based admission ("virtual waiting room").
- `SKIP LOCKED` batched expiry, or DB-clock-driven expiry, for very high volumes.
- Real authentication (OAuth2/JWT) replacing `X-Customer-Id`; per-customer purchase limits.
- Rate limiting and bot protection on hold creation.
- Metrics (Micrometer/Prometheus): holds granted/rejected, expiry lag, cache hit ratio, publish failures.
- Request-key retention/TTL cleanup; archival of final-state holds.
- Admin API for drop management; pagination for the drop list.
- Upgrade to Spring Boot 4.x.
