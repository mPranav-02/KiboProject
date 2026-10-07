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
- Final review phase: I had Claude Code check the build against the assignment PDF line by line, then ran
  `speckit-converge` (it compares the code to spec/plan/tasks and appends the remaining work as tasks). I did
  not accept its output wholesale: I had each convergence task re-checked against the code, picked only the
  ones that matter for the assignment, and ran `speckit-implement` on that subset alone.

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

### 5. Compose reads the committed example file
`docker-compose.yml` takes every value from `.env.example` (local-only placeholders) and an optional,
gitignored `.env` that overrides it. The MySQL and RabbitMQ containers map the shared `DB_*` / `RABBITMQ_*`
names to their own at start-up.

Why accepted:
- The PDF requires one-command startup (`docker-compose up --build`), and before this a fresh clone failed
  because `.env` is gitignored.
- The compose file still contains no hosts or credentials, so the constitution's "no hardcoded credentials"
  rule holds (see rejected suggestion 5 for the first attempt).
- Checked on a fresh copy of the repo with no `.env`: all four containers healthy, the full hold flow works,
  and a `.env` override (`KIBO_HOLD_DURATION=PT20S`) takes effect.

### 6. Transient database failures answer 503
Lock and query timeouts, deadlocks and other transient database errors return `503 SERVICE_UNAVAILABLE`
("nothing changed, safe to retry"). A failed commit returns 503 only when a lost connection caused it.

Why accepted:
- A client can tell "retry" apart from a bug (500), which is what failure handling under real-world pressure
  needs. Limiting the commit case to connection causes keeps real bugs visible as 500s.

### 7. Rerun the race suites with the brokers down
`ConcurrentReservationBrokersDownIT` and `HoldRaceBrokersDownIT` rerun the 200-request and confirm/cancel/
expire race suites with events switched on and both Redis and RabbitMQ unreachable.

Why accepted:
- The existing race tests ran with messaging off, so "correct with the brokers down" was not proven under
  contention. Reusing the same tests (subclasses) avoids a second copy of the race logic.
- I checked the run was not vacuous: the logs show about 2,900 failed publish attempts.

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

### 5. Credential defaults written into docker-compose.yml
The first fix for one-command startup put the placeholder passwords directly into the compose file
(`${DB_PASSWORD:-change-me-local-only}`).

Why rejected:
- It worked, but it broke the constitution's "no hardcoded credentials" rule. The next `speckit-converge` run
  flagged it as CRITICAL, and I had it replaced with accepted suggestion 5.

### 6. Implementing every convergence task
Why rejected:
- Several were real but low value for this scope: a p95 latency assertion (timing asserts are flaky on a
  laptop and in CI), an expiry-sweep stall that needs 200+ holds failing at once, multi-instance seeding (compose
  runs one instance), and bookkeeping. They stay open in `tasks.md`; the important ones are listed as known
  limitations in the README.
- One convergence task was already done (`docs/` was in git), which showed its output also needs checking.

### 7. Adding an admin `POST /drops` endpoint
Why rejected:
- The spec makes drop provisioning an operator task (seed data), and the PDF only asks for seeded drops. It is
  listed under "What I'd build next".

## Validation

How I convinced myself the result is correct:

- JUnit tests: unit tests (195) run without any infrastructure (`./mvnw test`). Integration tests (88) run
  against the official `mysql:8.4`, `redis:7` and `rabbitmq:4` images through Testcontainers
  (`./mvnw verify`). The risky parts have dedicated tests:
  - no oversell under concurrency (`ConcurrentReservationIT`);
  - confirm / cancel / expire races with exactly one winner and units returned once (`ConfirmCancelRaceIT`,
    `ExpirationIT`, `HoldRaceIT`);
  - the same concurrency and race suites with Redis and RabbitMQ unreachable (`ConcurrentReservationBrokersDownIT`,
    `HoldRaceBrokersDownIT`);
  - idempotent replays and transaction boundaries (`IdempotentHoldIT`, `TransactionBoundaryIT`);
  - cache staleness and eviction (`DropCacheIT`);
  - RabbitMQ events and the broker-down case (`RabbitEventsIT`, `RabbitOutageIT`).
  - A shared helper asserts the invariant `total - available == units in ACTIVE + CONFIRMED holds` after the
    concurrent tests.
- Heavy repetition run: `./mvnw verify -Dkibo.it.runs=100 -Dkibo.it.raceRuns=1000` (100 runs of the 200-request
  test, 1000 runs of each race) passed with 0 failures.
- Mockito tests: service and controller tests with mocked repositories, transaction manager, `RabbitTemplate`
  and cache, so the unit suite needs no infrastructure.
- Mutation checks: I removed the guards (the `available_quantity >= :q` condition, the `status = 'ACTIVE'`
  guard) and confirmed that the race tests then fail, so the tests can actually catch the bugs they claim to.
- Docker Compose end to end: `docker compose up --build` on a fresh copy of the repo with no `.env`: all
  containers healthy, drops seeded, and place / replay / confirm / cancel / rejection flows checked with curl.
- Postman: `postman/KIBO-Reservation.postman_collection.json` calls every endpoint and asserts each response
  (status, error `code`, state, availability). I ran it in the Postman app against the compose stack and every
  test passed, including the expiry folder with `KIBO_HOLD_DURATION=PT20S` (hold goes to `EXPIRED`, a late
  confirm gets `409 HOLD_EXPIRED`). The first expiry attempt failed for a test-design reason, not a service bug:
  the request reused an idempotency key, so the service correctly replayed an older 5-minute hold. The
  collection now generates a fresh key for that request.
- SpecKit: `speckit-analyze` before implementation; `speckit-converge` after it, twice, with each finding
  re-checked against the code before acting on it.
- AI mistakes caught by tests and review (so the output was not taken on trust):
  - a pooled-connection `LAST_INSERT_ID()` bug, replaced by `GeneratedKeyHolder`;
  - a flaky cache integration test caused by leaked reader threads;
  - a readiness endpoint that returned 404, which would have kept the compose healthcheck failing;
  - credential defaults in the compose file (rejected suggestion 5);
  - docs that named tests which do not exist (`InfrastructureDownIT`, `EndToEndIT` in an early quickstart) and
    a wrong claim that the app could run from the IDE against the compose stack as-is.
- IntelliJ debugging and a k6 load test: not performed; concurrency is proven by the JUnit/Testcontainers
  tests above.
