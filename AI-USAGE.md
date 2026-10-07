# AI Usage

AI was used for most of the typing, and I kept the decisions. Every design choice below traces to a written
artifact (constitution, spec, plan, `research.md`, `docs/HLD.md`) that I reviewed before any code was
generated, and every claim of correctness is backed by a test that I asked for and that can fail.

## Tools

### SpecKit
Purpose:
Requirements and implementation planning.

How context was given:
- A constitution of 17 non-negotiable principles first (never oversell; MySQL is the source of truth; Redis
  is only a cache; no JVM-local locking for correctness; simple over clever; AI output must be independently
  validated).
- Then `specify` -> `clarify` -> `plan` -> `checklist` -> `tasks` -> `analyze`. The outputs are in
  `specs/001-drop-reservation-service/` (`spec.md`, `plan.md`, `research.md` with decision / rationale /
  alternatives for each topic, `data-model.md`, `contracts/`, `tasks.md`), plus `docs/HLD.md` diagrams.
- Clarification questions I answered myself, for example: repeating a confirm or cancel is an idempotent
  success; the per-hold maximum applies, with no per-customer cap; units must be back within 10 s of expiry;
  a wrong customer sees "not found".

### Claude Code
Purpose:
Implementation, testing and refactoring.

How context was given:
- Every phase started with "read the assignment, constitution, spec, plan, tasks and HLD; do not redesign
  unless you find a concrete contradiction". Then I asked for one phase at a time (foundation, read APIs and
  seed, hold creation, confirm/cancel, expiration, Redis cache, RabbitMQ events).
- Each phase had to: explain the change first, implement only that phase, add tests, run them, fix failures,
  and report what was verified without silently expanding scope. Out-of-phase ideas had to be reported, not
  built.
- For hold creation I required an explanation of why the chosen SQL statement prevents two requests from
  reserving the same unit before any code was written.

## Accepted Suggestions

### 1. Atomic conditional inventory update
`UPDATE drops SET available_quantity = available_quantity - :q WHERE id = :id AND available_quantity >= :q`,
and the affected-row count is the verdict (1 = reserved, 0 = insufficient).

Why accepted:
- The check and the decrement are one statement. InnoDB takes the row lock, so concurrent requests serialize
  on that row, and the second one re-evaluates the condition against the committed value. There is no
  read-then-write window, so no oversell, regardless of how many app instances run.
- It needs no retry loop, unlike optimistic locking, which causes retry storms on a hot drop.
- `SELECT ... FOR UPDATE` was kept as a documented equivalent (`research.md` section 2). READ COMMITTED is
  configured to avoid gap locks.

### 2. Scheduled expiration
A `@Scheduled` job finds overdue ACTIVE holds without locks, then expires each one in its own transaction with
a guarded `UPDATE ... WHERE status = 'ACTIVE' AND expires_at <= now`.

Why accepted:
- The guarded update is the single winner among cancel, confirm and expire. Units are returned only on the
  one-row path, in the same transaction, so they are returned exactly once.
- It is safe on N instances without a distributed lock, because the guard makes duplicate work harmless. A
  hold touched after its deadline is also settled on contact, so a late confirm cannot revive an expired hold.
- One failing hold cannot block the batch, and expiry never depends on Redis or RabbitMQ.

### 3. Database transaction around inventory + hold
The inventory decrement and the insert of the ACTIVE hold happen in one explicit `TransactionTemplate`
boundary. Confirm, cancel and expire do the same for the status change and the unit return.

Why accepted:
- It is all-or-nothing: no units taken without a hold, no hold without units, and no returned units without
  the status change.
- An idempotency key (unique per customer) makes a repeated request return the same hold.
- Units are returned through one helper that throws `InventoryInvariantViolationException` if the return does
  not match, which rolls everything back.
- `TransactionBoundaryIT` verifies the boundaries, including that a failure after the decrement rolls it back.

### 4. After-commit side effects (cache eviction and RabbitMQ events)
Business code raises one in-process `HoldLifecycleEvent`. Listeners use `@TransactionalEventListener(AFTER_COMMIT)`.

Why accepted:
- Rolled-back work can never evict a cache entry or announce an event for something that did not happen.
- Messaging stays in its own package, and a test fails if business code starts depending on it.

## Rejected Suggestions

### 1. synchronized reservation method
Why rejected:
- `synchronized` or a `ReentrantLock` only protects one JVM. With two instances behind a load balancer (or a
  restart overlap) two requests can still take the last unit, which breaks the constitution's rule against
  JVM-local synchronization for distributed correctness.
- It also serializes all drops through one lock. The database row lock only serializes requests for the same drop.

### 2. Redis as inventory source of truth
Why rejected:
- A Redis `DECR` or Lua script as the gate can oversell after a Redis loss, failover or eviction, and the two
  stores can disagree. MySQL is the single authority.
- Redis is used only as a 3 s TTL read cache of drop data. Hold creation structurally cannot reference the
  cache (`CacheIsNotUsedForDecisionsTest`), and Redis outages fail open to MySQL (`DropCacheOutageIT`).

### 3. RabbitMQ-first reservation
Why rejected:
- Reserving through a queue (or driving expiry with delayed/TTL messages) makes correctness depend on the
  broker: an outage would stop sales, and a lost message would leave units stuck.
- Messaging is a side channel. Events are published after commit and are at-most-once, and the service works
  identically with the broker down or removed (`RabbitOutageIT`, `RabbitBrokerHangIT`).
- I also declined to build a transactional outbox for the two-day scope. It is documented as the future
  improvement in `docs/messaging-rabbitmq.md`.

### 4. Read-then-write status transitions (JPA dirty checking)
Why rejected:
- Load the hold, check its status, set the new status and save: cancel and expire can both read ACTIVE and
  both return the units. The guarded `UPDATE ... WHERE status = 'ACTIVE'` replaced it (`research.md`
  section 3).

## Validation

How I convinced myself the result is correct:

- JUnit tests: unit tests (158) run without any infrastructure. Integration tests (73) run against real MySQL
  through Testcontainers. The risky parts have dedicated tests:
  - no oversell under concurrency (`ConcurrentReservationIT`);
  - confirm / cancel / expire races with exactly one winner and units returned once (`ConfirmCancelRaceIT`,
    `ExpirationIT`, `HoldRaceIT`, repeated up to 1000 runs);
  - idempotent replays and transaction boundaries (`IdempotentHoldIT`, `TransactionBoundaryIT`);
  - cache staleness and eviction (`DropCacheIT`);
  - RabbitMQ events and the broker-down case (`RabbitEventsIT`, `RabbitOutageIT`).
  - A shared helper asserts the invariant `total - available == units in ACTIVE + CONFIRMED holds` after the
    concurrent tests.
- Mockito tests: service and controller tests with mocked repositories, transaction manager, `RabbitTemplate`
  and cache, so the unit suite needs no infrastructure.
- Mutation checks: I removed the guards (the `available_quantity >= :q` condition, the `status = 'ACTIVE'`
  guard) and confirmed that the race tests then fail, so the tests can actually catch the bugs they claim to.
- AI mistakes caught by tests and review (so the output was not taken on trust):
  - a pooled-connection `LAST_INSERT_ID()` bug, replaced by `GeneratedKeyHolder`;
  - a flaky cache integration test caused by leaked reader threads;
  - a readiness endpoint that returned 404, which would have kept the compose healthcheck failing.
- IntelliJ debugging: not yet performed.
- Postman API validation: not yet performed.
- k6 concurrency test: not yet performed (concurrency is currently proven by the JUnit/Testcontainers tests above).
- Docker Compose end-to-end validation: not yet performed. The `Dockerfile` and `docker-compose.yml` exist,
  but `docker-compose up --build` has not been run end to end.
- SpecKit convergence: `speckit-analyze` was run once on spec/plan/tasks; `speckit-converge` has not been run.

Known limits of this validation: the integration tests so far ran against stand-in MySQL 8.0, Redis 7 and
RabbitMQ 3.12 images (the sandbox could not pull the official ones), so `./mvnw verify` still has to be run
against `mysql:8.4`, `redis:7` and `rabbitmq:4`.
