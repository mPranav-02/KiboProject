# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

KIBO Limited Drop Reservation Service: Spring Boot 3.5 / Java 21 REST service that lets customers place
time-limited **holds** on units of a limited-stock **drop**, then confirm, cancel, or let them expire —
without ever overselling. MySQL 8.4 is the source of truth; Redis is a read cache only; RabbitMQ carries
after-the-fact lifecycle events only.

## Commands

```bash
./mvnw test                                   # unit + controller slice tests (*Test) — no Docker needed
./mvnw verify                                 # + Testcontainers integration tests (*IT) — Docker required
./mvnw test -Dtest=HoldServiceCreateTest      # single unit test class (append #method for one method)
./mvnw verify -Dit.test=HoldRaceIT -Dtest=skip -Dsurefire.failIfNoSpecifiedTests=false   # single IT
./mvnw verify -Dkibo.it.runs=100 -Dkibo.it.raceRuns=1000   # full-strength concurrency repetition counts

docker compose up --build -d      # full stack on :8080; reads .env.example, optional .env overrides
curl -s localhost:8080/actuator/health/readiness
docker compose down -v
```

Surefire runs only `**/*Test.java`; failsafe runs only `**/*IT.java` — the suffix decides which suite a test
belongs to. `application.yml` has **no defaults** for hosts/credentials (`DB_URL`, `DB_USERNAME`,
`DB_PASSWORD`, `REDIS_*`, `RABBITMQ_*`); startup fails if they're missing. `SPRING_PROFILES_ACTIVE=local` turns on Hibernate SQL logging,
but running from the IDE against compose infra doesn't work as-is: compose doesn't publish MySQL/Redis
ports, and `.env` hosts are compose service names.
`KIBO_HOLD_DURATION=PT20S` shortens holds for demoing expiry.

## Governing documents

- `.specify/memory/constitution.md` — 17 principles that override everything else. Key ones: never oversell;
  MySQL decides every grant/transition inside a transaction; Redis must never influence a decision; no
  JVM-local locking (`synchronized`, in-memory maps) for correctness — must be safe with N instances;
  business logic stays out of controllers; prefer simplicity; record architectural decisions.
- `specs/001-drop-reservation-service/` — spec, plan, `research.md` (decision/rationale/alternatives),
  `data-model.md`, `contracts/openapi.yaml`, `contracts/events.md`, `tasks.md`, `quickstart.md`.
  Note: quickstart's test table lists `InfrastructureDownIT`/`EndToEndIT`, which don't exist; the real
  equivalents are `DropCacheOutageIT`, `RabbitOutageIT`, `RabbitBrokerHangIT`, etc.
- `docs/HLD.md` (diagrams), `docs/caching-redis.md`, `docs/messaging-rabbitmq.md`, `AI-USAGE.md`.
- Work follows the Spec Kit flow (`/speckit-specify` → clarify → plan → tasks → implement); skills live in
  `.claude/skills/`.

## Architecture (package `com.kibo.reservation`)

- `api/` — controllers + DTOs only. Endpoints under `/api/v1`: `GET /drops`, `GET /drops/{id}`,
  `POST /drops/{id}/holds`, `GET /holds/{id}`, `POST /holds/{id}/confirm|cancel`. Required headers
  `X-Customer-Id` and (for create) `Idempotency-Key` (`ApiHeaders`). A hold owned by another customer is a 404.
- `application/` — all reservation logic: `HoldService` (create/confirm/cancel/get), `HoldExpirationService`
  + `HoldExpirationJob` (`@Scheduled` sweep), `DropQueryService`.
- `domain/` — JPA entities `Drop`, `Hold`; `HoldStatus` is the **single source of transition rules**
  (`allowedTargets`, `canTransitionTo`, `sourcesOf`). `TransitionRulesAreNotDuplicatedTest` enforces that no
  other production code hard-codes status lists. Exceptions map to `ErrorCode`s via
  `exception/GlobalExceptionHandler` (uniform error body; 409 for inventory/state conflicts).
- `repository/` — Spring Data repos whose `@Modifying` JPQL UPDATEs carry all concurrency safety.
- `cache/` — `DropCache` (Redis, short TTL, failures swallowed with WARN) + `DropCacheEvictionListener`
  (evicts on `AFTER_COMMIT`). `CacheIsNotUsedForDecisionsTest` guards that write paths never read the cache.
- `messaging/` — `RabbitHoldEventPublisher` is the only broker client: `@Async` + `@TransactionalEventListener(AFTER_COMMIT)`,
  publisher confirms, never retries. `AuditEventConsumer` is a demo consumer. `MessagingIsolationTest`
  guards that nothing else touches RabbitMQ.
- `config/` — `KiboProperties` (`kibo.hold.*`, `kibo.expiration.*`, `kibo.cache.*`, `kibo.seed.*`),
  `MessagingProperties`, injectable `Clock`, `DataSeeder` (seeds demo drops when `kibo.seed.enabled`).
- Schema is owned by Flyway (`src/main/resources/db/migration`); Hibernate is `ddl-auto: validate`, so schema
  changes need a new `V<n>__*.sql`.

### Concurrency model (read before touching hold/inventory code)

- **Create**: one transaction doing idempotency lookup → drop checks → atomic conditional decrement
  (`UPDATE drop SET available = available - q WHERE id = ? AND available >= q`, affected-row count decides) →
  INSERT ACTIVE hold. On a unique-constraint violation for (customer, idempotency key) a second read-only
  transaction replays the concurrently committed hold; same key with different quantity → 409.
- **Confirm/cancel/expire**: one guarded UPDATE `WHERE status IN (HoldStatus.sourcesOf(target)) AND expires_at
  >/<= now`. Exactly one competitor gets 1 row; only that winner returns units, in the same transaction —
  this is what gives exactly-once inventory return. Confirm/cancel finding an overdue hold settle it as
  EXPIRED and answer `HOLD_EXPIRED`. Repeating a confirm/cancel on an already-in-that-state hold is an
  idempotent 200.
- Expiry sweep runs on every instance; each hold expires in its own transaction, so overlapping sweeps are
  harmless (no ShedLock/leader election by design).
- Isolation is READ COMMITTED (set in Hikari config) — correctness comes from the conditional updates, not
  isolation level.
- Side effects (cache eviction, events) happen only after commit and must never affect the outcome; readiness
  depends on the DB only.

## Tests

- Unit/slice tests (`*Test`) use mocks/`@WebMvcTest` — no live infrastructure allowed.
- ITs (`it/*IT`) extend `AbstractMySqlIT` (shared real MySQL 8.4 Testcontainer, MockMvc, profile `test`:
  seeding off, sweep interval 1h so tests drive expiry explicitly, messaging off). Redis/RabbitMQ point at a
  closed port by default, so every IT also proves the service works without them; tests needing real
  ones register containers via `@DynamicPropertySource` (`DropCacheIT`, `AbstractRabbitIT`).
- ITs assert the invariant `total − available = Σ quantity(ACTIVE + CONFIRMED)` via `InvariantAssertions`;
  new inventory-affecting ITs should too.
