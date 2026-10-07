# Implementation Plan: Limited Drop Reservation Service

**Branch**: `001-drop-reservation-service` (spec directory; no git branch created, work is on `master`) | **Date**: 2026-10-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-drop-reservation-service/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Build a Java 21 / Spring Boot 3.5 REST service that lets customers browse limited "drops", place
time-limited holds on units, and confirm or cancel them, while unconfirmed holds expire automatically.
The defining requirement is **never overselling under concurrency**.

Technical approach (details in [research.md](./research.md)):

- **MySQL is the only authority** for inventory and hold state. A denormalized `drops.available_quantity`
  is changed only by **atomic conditional `UPDATE` statements**, and the affected-row count decides
  the outcome:
  - placing a hold: `... SET available = available - :q WHERE id = :id AND available >= :q AND starts_at <= :now`
  - releasing units: `... SET available = available + :q WHERE id = :id AND available + :q <= total`
- **Hold transitions are guarded updates**: `UPDATE holds SET status = :target WHERE id = :id AND status = 'ACTIVE' ...`.
  Only one competing confirm, cancel, or expire can affect the row, and inventory is returned in
  the same transaction, so it is returned exactly once.
- **Expiration**: a scheduled job (every 2s, configurable) finds overdue ACTIVE holds and expires each one
  with the same guarded transition in its own short transaction. Running it on several instances, or
  running it twice, is safe without distributed locks.
- **Redis** caches drop read models (cache-aside, short TTL, evicted after commit). Cache failures are
  logged and bypassed. Placing a hold never reads the cache.
- **RabbitMQ** receives `HOLD_CREATED/CONFIRMED/CANCELLED/EXPIRED` events from a separate messaging
  adapter, published asynchronously **after commit**. This is at-most-once delivery. The trade-off is
  documented, with a transactional outbox as the future improvement.
- **Layered modular monolith**: api → application → domain, plus repository, cache, messaging, config and
  exception packages. JUnit 5 + Mockito unit tests need no infrastructure. Testcontainers MySQL
  integration tests prove the concurrency guarantees. Docker Compose runs the app with MySQL, Redis and
  RabbitMQ.

## Technical Context

**Language/Version**: Java 21 (LTS)

**Primary Dependencies**:
- Spring Boot 3.5.x, using the latest 3.5 patch at implementation time. Modules: Web, Validation, Data
  JPA, Data Redis + Cache, AMQP, Actuator.
- Flyway (MySQL schema and constraints).
- MySQL Connector/J.
- Maven 3.9+.

**Storage**:
- MySQL 8.4 LTS: authoritative store for drops and holds; InnoDB with `READ COMMITTED` isolation.
- Redis 7: cache only.
- RabbitMQ 3.13/4.x: event publishing only.

**Testing**:
- JUnit 5, Mockito, AssertJ, Spring `@WebMvcTest` with MockMvc. Run by `mvn test` (Surefire, `*Test`)
  with no infrastructure.
- Testcontainers (MySQL; Redis and RabbitMQ for the end-to-end IT). Run by `mvn verify` (Failsafe,
  `*IT`); requires Docker.

**Target Platform**: Linux container (eclipse-temurin 21 JRE), run with Docker Compose locally.

**Project Type**: Web service (single deployable; layered modular monolith).

**Performance Goals**:
- SC-007: under 200 concurrent customers, p95 of hold, confirm and cancel requests is under 1s.
- SC-008: displayed availability is no more than 5s stale.
- FR-018: overdue holds are released within 10s.

**Constraints**:
- Zero oversell (FR-023/SC-001).
- Exactly-once release of units (FR-020).
- Correct with Redis or RabbitMQ down (FR-027).
- No JVM-local locks (Constitution IX).
- No hardcoded hosts or credentials (XIV).
- Two-day scope.

**Scale/Scope**: A handful of seeded drops; hundreds of concurrent requests on one hot drop; 6 REST
endpoints; 2 tables.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | How this plan complies | Pre-design | Post-design |
|---|-----------|------------------------|:---:|:---:|
| I | Correctness over premature optimization | Single hot-row conditional update; no sharding or counter tricks. Optimizations deferred to future-improvements doc. | ✅ | ✅ |
| II | Never oversell | Conditional decrement plus DB `CHECK (available_quantity >= 0 AND available_quantity <= total_quantity)` as defense in depth. Invariant asserted in ITs. | ✅ | ✅ |
| III | MySQL is source of truth | All grant, confirm, cancel and expire decisions run against MySQL in a transaction. Redis and RabbitMQ are never consulted for decisions. | ✅ | ✅ |
| IV | Transactional, concurrency-safe mutations | Atomic conditional `UPDATE` with affected-row check. Hold insert, or the state change plus inventory return, share one transaction. No read-then-write. | ✅ | ✅ |
| V | Redis only a cache | Cache-aside for drop reads only. `CacheErrorHandler` logs and falls through. TTL bounds staleness. Never used for hold decisions. | ✅ | ✅ |
| VI | Explicit, valid transitions | `HoldStatus` enum owns the transition table. Persistence guards every transition with `status = 'ACTIVE'`. Invalid transitions raise domain exceptions mapped to 409. | ✅ | ✅ |
| VII | Hold lifecycle rules | Only ACTIVE→{CONFIRMED, CANCELLED, EXPIRED}. Confirm and cancel also guard `expires_at > :now`. | ✅ | ✅ |
| VIII | Exactly-once inventory return | Return happens only when the guarded transition affected 1 row, in the same transaction. Guarded increment `available + q <= total`. Tested via cancel/expire races and repeated sweeps. | ✅ | ✅ |
| IX | No JVM-local synchronization | No `synchronized`, locks or in-memory maps for correctness. Expiry job is safe on N instances without ShedLock. | ✅ | ✅ |
| X | Logic separated from controllers and infra | Controllers map HTTP↔DTO only. Rules live in application and domain. Repositories, cache and messaging sit behind interfaces or adapters and are mocked in unit tests. | ✅ | ✅ |
| XI | REST semantics, structured errors | 201/200/400/404/409/503; RFC 7807 `ProblemDetail` with stable `code` via one `@RestControllerAdvice`. See [contracts/openapi.yaml](./contracts/openapi.yaml). | ✅ | ✅ |
| XII | Tests target concurrency, transitions, expiry, failure | Test matrix in research.md §10. Concurrency, race, idempotency, expiry and infra-down ITs are first-class tasks. | ✅ | ✅ |
| XIII | Unit tests without live infra | `mvn test` uses Mockito and WebMvcTest only. DB-level guarantees are proven in separate `*IT` Testcontainers tests. | ✅ | ✅ |
| XIV | No hardcoded hosts or credentials | `application.yml` uses `${ENV}` with no host or credential defaults. `docker-compose.yml` holds no credentials: every service reads the committed local-only placeholders in `.env.example`, then an optional gitignored `.env` that overrides them, so `docker compose up --build` works on a fresh clone. | ✅ | ✅ |
| XV | Simplicity | One deployable, two tables, no distributed locks, no outbox, no consumers. Redis and RabbitMQ are justified below. | ⚠️ justified | ⚠️ justified |
| XVI | AI code reviewed and validated | Tasks include a human review checkpoint per story and require running ITs and inspecting SQL logs before acceptance. | ✅ | ✅ |
| XVII | Decisions documented | research.md records decisions and rejected alternatives. Implementation produces `docs/*.md` and `docs/decisions/` ADRs. | ✅ | ✅ |

**Gate result**: PASS. The XV item is justified in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/001-drop-reservation-service/
├── plan.md              # This file
├── research.md          # Phase 0: decisions, rationale, rejected alternatives
├── data-model.md        # Phase 1: tables, constraints, state machine, invariants
├── quickstart.md        # Phase 1: run & validation guide
├── contracts/
│   ├── openapi.yaml     # REST contract (6 endpoints, DTOs, error model)
│   └── events.md        # RabbitMQ event contract
└── tasks.md             # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
pom.xml
Dockerfile
docker-compose.yml
.env.example                       # local-only placeholders read by compose; optional .env (gitignored) overrides
README.md                          # overview, how to run, links to docs/
docs/
├── architecture.md
├── concurrency-and-transactions.md   # concurrency strategy + transaction boundaries
├── caching-redis.md                  # Redis trade-offs, invalidation & consistency
├── messaging-rabbitmq.md             # commit/publish trade-off, outbox as future work
├── expiration.md
├── testing-strategy.md
├── future-improvements.md
└── decisions/                        # ADRs (0001-…md): decision, alternatives, why rejected

src/main/java/com/kibo/reservation/
├── KiboReservationApplication.java
├── api/                       # REST controllers (DropController, HoldController)
│   └── dto/                   # Request/response records + mappers (no entities exposed)
├── application/               # Use cases: DropQueryService, HoldService, HoldExpirationService,
│                              #   HoldExpirationJob (@Scheduled), retry helper
├── domain/                    # Drop, Hold (JPA-annotated), HoldStatus (transition table),
│                              #   DropAvailabilityStatus, domain exceptions, HoldLifecycleEvent
├── repository/                # DropRepository, HoldRepository (atomic @Modifying queries)
├── cache/                     # Redis cache config, after-commit eviction, LoggingCacheErrorHandler
├── messaging/                 # RabbitMQ topology, AFTER_COMMIT async publisher, message DTO
├── config/                    # Clock, @ConfigurationProperties, async/scheduling config, DataSeeder
└── exception/                 # GlobalExceptionHandler (@RestControllerAdvice), ErrorCode

src/main/resources/
├── application.yml            # all infra via ${ENV}; no hosts/credentials
└── db/migration/
    └── V1__create_drops_and_holds.sql

src/test/java/com/kibo/reservation/
├── domain/                    # HoldStatusTest, HoldTest (pure unit)
├── application/               # HoldServiceTest, HoldExpirationServiceTest, DropQueryServiceTest (Mockito)
├── api/                       # DropControllerTest, HoldControllerTest (@WebMvcTest), error mapping
├── cache/  messaging/         # error-handler / publisher adapter unit tests (mocks)
└── it/                        # *IT: Testcontainers MySQL (+Redis/RabbitMQ for E2E)
    ├── ConcurrentReservationIT.java     # SC-001 no oversell
    ├── HoldRaceIT.java                  # SC-002 confirm/cancel vs expire
    ├── IdempotentHoldIT.java            # SC-003
    ├── ExpirationIT.java                # SC-004, repeated/multi-sweeper
    ├── InfrastructureDownIT.java        # SC-005 Redis/RabbitMQ unavailable
    └── EndToEndIT.java                  # REST + cache eviction + event published
```

**Structure Decision**: One Maven module (modular monolith) with package-per-layer as requested. Domain
classes carry JPA annotations rather than separate persistence models. That avoids a mapping layer within
the two-day scope, and the domain stays testable because repositories are interfaces (research.md §1).

## Complexity Tracking

| Violation / added complexity | Why needed | Simpler alternative rejected because |
|------------------------------|------------|--------------------------------------|
| Redis cache (XV) | Required by the assignment; drop reads are the read-heavy path | No cache is simpler but doesn't meet the brief. Risk is contained: read-only, TTL-bounded, failure-tolerant, never used for decisions. |
| RabbitMQ events with no consumer in scope (XV) | Required by the assignment; lets downstream systems react to lifecycle changes | Logging only doesn't meet the brief. Kept in an isolated adapter, published after commit, never affects correctness. |
| Demo `AuditEventConsumer` on `kibo.holds.audit` (XV, added during implementation) | Shows the topology working end to end: each event is logged by the app as it arrives | Leaving the queue unconsumed lets it grow forever on a long-running stack. The consumer only logs, never affects state, and is switched off with `kibo.messaging.audit-consumer-enabled=false` (then events stay visible in the queue). |
| Flyway (not in the requested stack) | CHECK constraints, unique key and indexes are part of the correctness design and must be versioned | `ddl-auto` can't reliably create CHECK constraints or named indexes, and isn't safe for repeatable schemas. |
| Testcontainers (not in the requested stack) | Constitution XIII requires DB-level concurrency to be proven against real MySQL | H2 lacks MySQL's locking semantics, so it would give false confidence in no-oversell. |
