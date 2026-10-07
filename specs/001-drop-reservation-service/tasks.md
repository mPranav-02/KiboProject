---

description: "Task list for the KIBO Limited Drop Reservation Service"
---

# Tasks: Limited Drop Reservation Service

**Input**: Design documents from `/specs/001-drop-reservation-service/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi.yaml, contracts/events.md, quickstart.md

**Tests**: INCLUDED. The constitution (XII, XIII) and the plan's testing strategy require them. Unit tests
(`*Test`, `mvn test`) need no infrastructure. Integration tests (`*IT`, `mvn verify`) use Testcontainers.
Write each story's tests first and make sure they fail before implementing.

**Organization**: Tasks are grouped by user story so each story can be built and tested on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete tasks)
- **[Story]**: User story from spec.md (US1–US6)
- Paths: main code `src/main/java/com/kibo/reservation/…`, tests `src/test/java/com/kibo/reservation/…`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project skeleton, build, local runtime

- [X] T001 Create Maven project `pom.xml`:
  - Java 21; Spring Boot 3.5.x parent.
  - Starters: web, validation, data-jpa, data-redis, cache, amqp, actuator.
  - Libraries: flyway-core, flyway-mysql, mysql-connector-j.
  - Test dependencies: spring-boot-starter-test, spring-boot-testcontainers, testcontainers mysql, junit-jupiter.
  - Plugins: Surefire runs `*Test`; Failsafe runs `*IT`.
- [X] T002 Create application entry point `src/main/java/com/kibo/reservation/KiboReservationApplication.java`
  with `@EnableScheduling` and `@EnableAsync`.
- [X] T003 [P] Create `src/main/resources/application.yml`:
  - All infra settings read from env with NO host or credential defaults: `DB_URL`, `DB_USERNAME`,
    `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_PORT`,
    `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`.
  - Hikari `transaction-isolation: TRANSACTION_READ_COMMITTED`.
  - `spring.jpa.hibernate.ddl-auto=validate`, `hibernate.jdbc.time_zone=UTC`.
  - Business defaults: `kibo.hold.duration=PT5M`, `kibo.hold.default-max-per-hold=4`,
    `kibo.expiration.interval=PT2S`, `kibo.expiration.batch-size=200`, `kibo.cache.drop-ttl=PT3S`,
    `kibo.seed.enabled=true`.
  - `logging.structured.format.console=ecs`.
  - Actuator exposes `health`, with readiness group `include: db`.
- [X] T004 [P] Create `.env.example` (clearly marked local-only placeholder values for every variable in
  T003, plus `MYSQL_ROOT_PASSWORD`) and `.gitignore` (ignore `.env`, `target/`, `.idea/`).
- [X] T005 [P] Create multi-stage `Dockerfile` (maven:3.9-eclipse-temurin-21 build → eclipse-temurin:21-jre
  runtime, non-root user, exposes 8080).
- [X] T006 [P] Create `docker-compose.yml`:
  - Services: `app`, `mysql` (8.4), `redis` (7), `rabbitmq` (management image, ports 5672/15672).
  - Each service has a healthcheck; `app` uses `depends_on: condition: service_healthy`.
  - `env_file: .env`, a named volume for MySQL, and no hardcoded credentials.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema, domain model, transactional primitives, error handling and test harness that every
story depends on

**⚠️ CRITICAL**: No user story work begins until this phase is complete

- [X] T007 Create Flyway migration `src/main/resources/db/migration/V1__create_drops_and_holds.sql`:
  - **`drops`**:
    - `id BIGINT AUTO_INCREMENT PK`
    - `name VARCHAR(200) NOT NULL`
    - `description VARCHAR(2000) NULL`
    - `total_quantity INT NOT NULL`
    - `available_quantity INT NOT NULL`
    - `max_per_hold INT NOT NULL DEFAULT 4`
    - `starts_at`, `created_at`, `updated_at`: `DATETIME(6) NOT NULL`
    - `CHECK (total_quantity > 0)`
    - `CHECK (available_quantity >= 0 AND available_quantity <= total_quantity)`
    - `CHECK (max_per_hold >= 1)`
  - **`holds`**:
    - `id CHAR(36) PK`
    - `drop_id BIGINT NOT NULL FK→drops.id`
    - `customer_id VARCHAR(64) NOT NULL`
    - `request_key VARCHAR(64) NOT NULL`
    - `quantity INT NOT NULL`
    - `status VARCHAR(16) NOT NULL`
    - `expires_at DATETIME(6) NOT NULL`
    - `resolved_at DATETIME(6) NULL`
    - `created_at`, `updated_at`: `DATETIME(6) NOT NULL`
    - `CHECK (quantity >= 1)`
    - `CHECK (status IN ('ACTIVE','CONFIRMED','CANCELLED','EXPIRED'))`
    - `UNIQUE uk_holds_customer_request (customer_id, request_key)`
    - `INDEX ix_holds_status_expires (status, expires_at)`
    - `INDEX ix_holds_drop (drop_id)`
- [X] T008 [P] Create `HoldStatus` enum with an explicit transition table (ACTIVE → {CONFIRMED, CANCELLED,
  EXPIRED}; final states have none) and `canTransitionTo(target)`/`isFinal()` in
  `src/main/java/com/kibo/reservation/domain/HoldStatus.java`.
- [X] T009 [P] Create `DropAvailabilityStatus` enum with
  `static of(startsAt, availableQuantity, now)` → UPCOMING if `now < startsAt`, else SOLD_OUT if
  `available == 0`, else OPEN, in `src/main/java/com/kibo/reservation/domain/DropAvailabilityStatus.java`.
- [X] T010 [P] Create the JPA entity `Drop` (fields per data-model.md; no setters for quantities) in
  `src/main/java/com/kibo/reservation/domain/Drop.java`.
- [X] T011 [P] Create the JPA entity `Hold` (UUID id; `effectiveStatus(now)` returns EXPIRED when
  `status == ACTIVE && !now.isBefore(expiresAt)`) in `src/main/java/com/kibo/reservation/domain/Hold.java`.
- [X] T012 [P] Create domain exceptions, each carrying an `ErrorCode`, in
  `src/main/java/com/kibo/reservation/domain/exception/`: `DomainException` (base),
  `DropNotFoundException`, `HoldNotFoundException`, `DropNotReleasedException`,
  `InsufficientInventoryException` (with availableQuantity), `HoldExpiredException`,
  `InvalidStateTransitionException` (with currentStatus), `IdempotencyKeyConflictException`,
  `InventoryInvariantViolationException`.
  - _Status: done. `InventoryInvariantViolationException` deliberately extends `RuntimeException` (not `DomainException`) so it is answered as a generic 500 and its message is never returned._
- [X] T013 [P] Create the `ErrorCode` enum (VALIDATION_ERROR, DROP_NOT_FOUND, HOLD_NOT_FOUND,
  DROP_NOT_RELEASED, INSUFFICIENT_INVENTORY, HOLD_EXPIRED, INVALID_STATE_TRANSITION,
  IDEMPOTENCY_KEY_CONFLICT, SERVICE_UNAVAILABLE, INTERNAL_ERROR), each with an HTTP status, in
  `src/main/java/com/kibo/reservation/exception/ErrorCode.java`.
- [X] T014 Create `GlobalExceptionHandler` (`@RestControllerAdvice`) in
  `src/main/java/com/kibo/reservation/exception/GlobalExceptionHandler.java`:
  - Returns RFC 7807 `ProblemDetail` with `code` and `timestamp`, plus `currentStatus`,
    `availableQuantity` and `errors[]` where relevant.
  - Maps bean-validation, missing-header and type-mismatch errors to 400.
  - Maps DB connectivity and exhausted lock retries to 503.
  - Maps anything else to 500 with no stack trace.
- [X] T015 [P] Create `DropRepository` in `src/main/java/com/kibo/reservation/repository/DropRepository.java`.
  Both methods are `@Modifying(clearAutomatically = true, flushAutomatically = true)` and return the
  affected-row count:
  - `reserveUnits(id, qty, now)`:
    `UPDATE Drop d SET d.availableQuantity = d.availableQuantity - :qty, d.updatedAt = :now WHERE d.id = :id AND d.availableQuantity >= :qty AND d.startsAt <= :now`.
  - `releaseUnits(id, qty, now)`:
    `... + :qty ... WHERE d.id = :id AND d.availableQuantity + :qty <= d.totalQuantity`.
  - _Status: done (`reserveUnits` and `releaseUnits`)._
- [X] T016 [P] Create `HoldRepository` in `src/main/java/com/kibo/reservation/repository/HoldRepository.java`:
  - `findByCustomerIdAndRequestKey`.
  - `findOverdueActiveIds(now, Pageable)`: `status = ACTIVE AND expiresAt <= :now ORDER BY expiresAt`.
  - Guarded `@Modifying` transitions, each returning the affected-row count:
    - `confirm(id, customerId, now)` and `cancel(id, customerId, now)`:
      `WHERE status = 'ACTIVE' AND expiresAt > :now`.
    - `expire(id, now)`: `WHERE status = 'ACTIVE' AND expiresAt <= :now`.
    - All three set `resolvedAt` and `updatedAt`.
  - _Status: done (`findByCustomerIdAndRequestKey`, guarded `confirm`/`cancel`/`expire`, `findOverdueActiveIds`)._
- [ ] T017 Create `HoldTransitions` (`@Transactional`) in
  `src/main/java/com/kibo/reservation/application/HoldTransitions.java`:
  - `expireAndRelease(holdId, now)`: guarded expire; release units only if 1 row changed; throw
    `InventoryInvariantViolationException` if the release affects 0 rows.
  - `cancelAndRelease(holdId, customerId, now)`: same rule.
  - Each publishes a `HoldLifecycleEvent` only on the 1-row path. Shared by US2, US3 and US4.
  - _Status: PARTIAL: no `HoldTransitions` class. Cancel stays in `HoldService`, expire is in `HoldExpirationService`, and both return units through the shared `UnitRelease` (same rule: release only on the 1-row path, throw `InventoryInvariantViolationException` otherwise). Lifecycle events are not published yet (messaging phase)._
- [X] T018 [P] Create the `HoldLifecycleEvent` record (eventId, type HOLD_CREATED|CONFIRMED|CANCELLED|EXPIRED,
  occurredAt, holdId, dropId, customerId, quantity, status, expiresAt) in
  `src/main/java/com/kibo/reservation/domain/event/HoldLifecycleEvent.java`.
  - _Status: done at `domain/event/HoldLifecycleEvent.java` (+ `Type.changesAvailability()`). Raised in-process inside the transaction on every real change (created, confirmed, cancelled, expired) and never for replays, repeats or rejections. Today only the cache listener consumes it; the RabbitMQ publisher (Phase 9) will listen to the same event._
- [X] T019 [P] Create config classes in `src/main/java/com/kibo/reservation/config/`:
  - `ClockConfig.java`: UTC `Clock` bean.
  - `KiboProperties.java`: `@ConfigurationProperties` for hold duration, default max-per-hold,
    expiration interval and batch size, cache TTL, seed enabled.
- [ ] T020 [P] Create `TransactionRetry` in `src/main/java/com/kibo/reservation/application/TransactionRetry.java`:
  - Retries `CannotAcquireLockException` and `PessimisticLockingFailureException` up to 3 attempts with
    jittered backoff.
  - Invoked outside the transactional proxy.
  - Throws `ServiceUnavailableException` when attempts are exhausted.
- [X] T021 [P] Create `DataSeeder` (`ApplicationRunner`) in `src/main/java/com/kibo/reservation/config/DataSeeder.java`:
  - Only if the drops table is empty AND `kibo.seed.enabled`.
  - Inserts 4 drops relative to now: open 50 units, open 5 units, open 1 unit ("last unit"), upcoming
    (+10 min) 20 units.
- [X] T022 [P] Create request-header validation helpers in
  `src/main/java/com/kibo/reservation/api/HeaderValidation.java`: `X-Customer-Id` and `Idempotency-Key`
  must be 1–64 chars matching `^[A-Za-z0-9._:-]+$`, otherwise VALIDATION_ERROR.
- [X] T023 [P] Unit test the transition table, every (from, to) pair, in
  `src/test/java/com/kibo/reservation/domain/HoldStatusTest.java`. Also unit test
  `DropAvailabilityStatus.of` and `Hold.effectiveStatus` boundaries (exactly at `startsAt` and at
  `expiresAt`) in `src/test/java/com/kibo/reservation/domain/DomainStatusTest.java`.
- [ ] T024 [P] Unit test `HoldTransitions` in `src/test/java/com/kibo/reservation/application/HoldTransitionsTest.java`
  with mocked repositories:
  - release happens only when the guarded update returns 1;
  - a 0-row update releases nothing and publishes nothing;
  - a 0-row release throws the invariant exception.
- [X] T025 Create the Testcontainers base class `AbstractMySqlIT` in
  `src/test/java/com/kibo/reservation/it/AbstractMySqlIT.java`:
  - MySQL 8.4 container, `@ServiceConnection`, seeding disabled.
  - Redis and RabbitMQ auto-configuration pointed at unused ports or disabled.
  - Adds `InvariantAssertions.assertInventoryInvariant(dropId)`: `total − available == Σ quantity` where
    status IN (ACTIVE, CONFIRMED), and `available >= 0`.
- [ ] T026 [P] Unit test `GlobalExceptionHandler` in
  `src/test/java/com/kibo/reservation/exception/GlobalExceptionHandlerTest.java`: every `ErrorCode` maps to
  its status and a ProblemDetail with `code` and `timestamp`, and no stack trace leaks.

**Checkpoint**: Schema migrates, domain and transitions are unit-tested, and the IT harness runs.

---

## Phase 3: User Story 1 - Place a Hold Without Overselling (Priority: P1) 🎯 MVP

**Goal**: Customers place all-or-nothing, idempotent holds on released drops; concurrent requests never oversell.

**Independent Test**: Seed a 50-unit drop and fire 200 concurrent 1-unit requests. Exactly 50 succeed,
available is 0, and the invariant holds (SC-001). Retries with the same key create no extra hold (SC-003).

### Tests for User Story 1 (write first, must fail)

- [X] T027 [P] [US1] Unit test hold placement in `src/test/java/com/kibo/reservation/application/HoldServiceCreateTest.java`
  (Mockito, fixed Clock). Cases:
  - success (status ACTIVE, `expiresAt = now + duration`);
  - quantity 0, or above `maxPerHold` → VALIDATION_ERROR;
  - drop not found; not yet released; insufficient inventory (with availableQuantity);
  - replay with the same key and details returns the original hold and does no decrement;
  - same key with a different drop or quantity → IDEMPOTENCY_KEY_CONFLICT;
  - 0-row decrement that finds the key on re-check → replay;
  - unique-key violation on insert → replay of the existing hold.
- [X] T028 [P] [US1] `@WebMvcTest` for `POST /api/v1/drops/{dropId}/holds` in
  `src/test/java/com/kibo/reservation/api/HoldControllerCreateTest.java`:
  - 201 with `Location`; 200 on replay;
  - 400 when `X-Customer-Id` or `Idempotency-Key` is missing or invalid, or the body is invalid;
  - 404 DROP_NOT_FOUND;
  - 409 for INSUFFICIENT_INVENTORY, DROP_NOT_RELEASED and IDEMPOTENCY_KEY_CONFLICT;
  - response body matches the `HoldResponse` schema in contracts/openapi.yaml.
- [X] T029 [P] [US1] `ConcurrentReservationIT` in `src/test/java/com/kibo/reservation/it/ConcurrentReservationIT.java`:
  - 200 threads behind a `CountDownLatch` start gate; drop of 50 units.
  - Asserts 50 successes, 150 INSUFFICIENT_INVENTORY, available 0, and the invariant.
  - Repeats `-Dkibo.it.runs` times (default 20).
  - Mixed-quantity case: 5 units left, requests for 3, 3 and 2. Each is fully granted or fully rejected,
    and no more than 5 units are granted in total.
- [X] T030 [P] [US1] `IdempotentHoldIT` in `src/test/java/com/kibo/reservation/it/IdempotentHoldIT.java`:
  10 concurrent requests with the same customer and key give exactly 1 hold row and one decrement (SC-003).

### Implementation for User Story 1

- [X] T031 [P] [US1] Create the `CreateHoldRequest` record (`@NotNull @Min(1) Integer quantity`) and the
  `HoldResponse` record (id, dropId, quantity, status, createdAt, expiresAt, resolvedAt) in
  `src/main/java/com/kibo/reservation/api/dto/`.
- [X] T032 [P] [US1] Create `HoldMapper` in `src/main/java/com/kibo/reservation/api/dto/HoldMapper.java`.
  It maps Hold → HoldResponse using `effectiveStatus(now)` (FR-019) and never exposes the entity.
- [X] T033 [US1] Implement `HoldCreation` (`@Transactional`) in
  `src/main/java/com/kibo/reservation/application/HoldCreation.java`, as research.md §2 orders it:
  1. Key lookup.
  2. `reserveUnits`.
  3. On 1 row: insert the ACTIVE hold with `saveAndFlush` and publish HOLD_CREATED.
  4. On 0 rows: re-check the key, then classify as DROP_NOT_FOUND, DROP_NOT_RELEASED or
     INSUFFICIENT_INVENTORY.
  Validate quantity against `drop.maxPerHold`.
- [X] T034 [US1] Implement `HoldService.placeHold(dropId, customerId, requestKey, quantity)` in
  `src/main/java/com/kibo/reservation/application/HoldService.java`. It is non-transactional:
  - wraps `HoldCreation` in `TransactionRetry`;
  - on `DataIntegrityViolationException` for `uk_holds_customer_request`, reads and returns the existing
    hold (replay), or IDEMPOTENCY_KEY_CONFLICT if the details differ;
  - returns a result flag saying whether the hold was created or replayed.
- [X] T035 [US1] Implement `POST /api/v1/drops/{dropId}/holds` in
  `src/main/java/com/kibo/reservation/api/HoldController.java`:
  - validates headers with `HeaderValidation`;
  - returns 201 + `Location: /api/v1/holds/{id}` when created, 200 when replayed.
- [X] T036 [US1] Add structured logging (holdId, dropId, customerId, quantity, outcome/code) for every
  hold placement and rejection in `src/main/java/com/kibo/reservation/application/HoldService.java` (FR-031).

**Checkpoint**: US1 works alone. T029 and T030 are green, and holds can be placed through curl.

---

## Phase 4: User Story 2 - Confirm an Active Hold (Priority: P1)

**Goal**: Customers confirm ACTIVE holds before expiry. A repeated confirm is idempotent; an expired or
invalid confirm is rejected.

**Independent Test**: Create a hold and confirm it: status CONFIRMED, units stay consumed. Confirm again:
200 with no change. Confirm an overdue hold: 409 HOLD_EXPIRED and the hold settles to EXPIRED.

### Tests for User Story 2

- [X] T037 [P] [US2] Unit test confirm in `src/test/java/com/kibo/reservation/application/HoldServiceConfirmTest.java`:
  - ACTIVE → CONFIRMED;
  - repeat on CONFIRMED → idempotent success with no update;
  - overdue ACTIVE → HOLD_EXPIRED and `expireAndRelease` invoked;
  - EXPIRED → HOLD_EXPIRED; CANCELLED → INVALID_STATE_TRANSITION (with currentStatus);
  - wrong customer or unknown id → HOLD_NOT_FOUND.
  - _Status: done in `HoldServiceTransitionTest` (confirm and cancel unit tests share one class). The overdue case asserts HOLD_EXPIRED; settling to EXPIRED via `expireAndRelease` is deferred to US3._
- [X] T038 [P] [US2] `@WebMvcTest` for `POST /api/v1/holds/{holdId}/confirm` in
  `src/test/java/com/kibo/reservation/api/HoldControllerConfirmTest.java`: 200, 404 (including a malformed
  UUID), 409 HOLD_EXPIRED and 409 INVALID_STATE_TRANSITION with `currentStatus`.
- [X] T039 [P] [US2] `ConfirmIT` in `src/test/java/com/kibo/reservation/it/ConfirmIT.java`:
  - confirm keeps units consumed and the invariant holds;
  - confirm at exactly `expiresAt` is rejected (Clock fixed at the boundary).
  - _Status: done in `ConfirmCancelIT` (the boundary is asserted against the repository guard with explicit timestamps)._

### Implementation for User Story 2

- [X] T040 [US2] Implement `HoldConfirmation` (`@Transactional`) in
  `src/main/java/com/kibo/reservation/application/HoldConfirmation.java`:
  - guarded `confirm`; on 1 row, publish HOLD_CONFIRMED;
  - on 0 rows, load and classify: HOLD_NOT_FOUND for a missing hold or wrong customer; idempotent for
    CONFIRMED; HOLD_EXPIRED for EXPIRED or overdue; INVALID_STATE_TRANSITION otherwise.
  - _Status: done inside `HoldService.transitionInTransaction` (no separate `HoldConfirmation` class: one explicit transaction for both confirm and cancel)._
- [X] T041 [US2] Implement `HoldService.confirm(holdId, customerId)` in
  `src/main/java/com/kibo/reservation/application/HoldService.java`:
  - uses `TransactionRetry`;
  - on an overdue hold, calls `HoldTransitions.expireAndRelease` in its own transaction before throwing
    HOLD_EXPIRED;
  - logs the outcome (FR-031).
  - _Status: done: an overdue hold is rejected with HOLD_EXPIRED and settled on contact via `HoldExpirationService.expireOne` in its own transaction (best effort). `TransactionRetry` (T020) is still deferred._
- [X] T042 [US2] Add `POST /api/v1/holds/{holdId}/confirm` to
  `src/main/java/com/kibo/reservation/api/HoldController.java` (a malformed UUID gives 404 HOLD_NOT_FOUND).

**Checkpoint**: US1 and US2 both work independently.

---

## Phase 5: User Story 3 - Automatic Expiration of Unconfirmed Holds (Priority: P2)

**Goal**: Overdue ACTIVE holds expire without any customer action and release units exactly once, within 10s.

**Independent Test**: Create a hold with a short duration and wait. It becomes EXPIRED, availability is
restored once, and confirming it is rejected. Concurrent or repeated sweeps never double-release (SC-004).

### Tests for User Story 3

- [X] T043 [P] [US3] Unit test `HoldExpirationService` in
  `src/test/java/com/kibo/reservation/application/HoldExpirationServiceTest.java`:
  - batches until a short page is returned;
  - per-hold failure is logged and the batch continues;
  - a 0-row expire is a no-op.
  - _Status: done in `HoldExpirationServiceTest` (+ `HoldExpirationJobTest`)._
- [X] T044 [P] [US3] `ExpirationIT` in `src/test/java/com/kibo/reservation/it/ExpirationIT.java`:
  - overdue holds become EXPIRED and units are restored;
  - 3 concurrent sweeper threads plus repeated sweeps release each hold exactly once (invariant);
  - a hold overdue at "restart" is expired on the first sweep;
  - confirm after expiry → HOLD_EXPIRED.
  - _Status: done in `ExpirationIT` (+ `HoldExpirationJobIT` proving the real `@Scheduled` job)._
- [X] T045 [US3] Create `HoldRaceIT` in `src/test/java/com/kibo/reservation/it/HoldRaceIT.java` with
  **confirm vs expire**, run `-Dkibo.it.raceRuns` times (default 200):
  - the two operations use clocks either side of `expiresAt`;
  - asserts exactly one final state and `available == total − confirmed` (SC-002).
  - _Status: done in `HoldRaceIT`; property is `kibo.it.raceRuns` (default 200). Also covers confirm + cancel + expire all at once._

### Implementation for User Story 3

- [X] T046 [US3] Implement `HoldExpirationService.expireOverdue(now)` in
  `src/main/java/com/kibo/reservation/application/HoldExpirationService.java`:
  - pages `findOverdueActiveIds` by `kibo.expiration.batch-size`;
  - calls `HoldTransitions.expireAndRelease` per id (own transaction, via `TransactionRetry`);
  - loops while a page is full.
  - _Status: done as `expireOverdue(now)` + `expireOne(id, now)`; the per-hold transaction lives in `HoldExpirationService` itself (no `HoldTransitions`). No `TransactionRetry`: a failed hold is retried by the next sweep._
- [X] T047 [US3] Implement `HoldExpirationJob` in
  `src/main/java/com/kibo/reservation/application/HoldExpirationJob.java`:
  - `@Scheduled(fixedDelayString = "${kibo.expiration.interval}")` calling `expireOverdue(clock.instant())`;
  - logs a summary (expired count, duration) and each expiry (FR-031);
  - no JVM locks or ShedLock.
  - _Status: done; first run is one interval after startup (`initialDelay`), so tests with `PT1H` never see a background sweep._

**Checkpoint**: Expiry runs on its own. The confirm-vs-expire race is proven.

---

## Phase 6: User Story 4 - Cancel an Active Hold (Priority: P2)

**Goal**: Customers cancel ACTIVE holds, returning units exactly once. A repeated cancel is idempotent;
cancelling a confirmed or expired hold is rejected.

**Independent Test**: Cancel returns units once. Cancel again: 200 with no change. Cancel vs expire and
cancel vs confirm races each produce exactly one final state.

### Tests for User Story 4

- [X] T048 [P] [US4] Unit test cancel in `src/test/java/com/kibo/reservation/application/HoldServiceCancelTest.java`:
  - ACTIVE → CANCELLED with release;
  - repeat on CANCELLED → idempotent with no release;
  - CONFIRMED → INVALID_STATE_TRANSITION;
  - EXPIRED or overdue → HOLD_EXPIRED and settle;
  - wrong customer → HOLD_NOT_FOUND.
  - _Status: done in `HoldServiceTransitionTest`._
- [X] T049 [P] [US4] `@WebMvcTest` for `POST /api/v1/holds/{holdId}/cancel` in
  `src/test/java/com/kibo/reservation/api/HoldControllerCancelTest.java`: 200, 404, and 409 with
  `currentStatus`.
  - _Status: done in `HoldControllerTransitionTest`._
- [X] T050 [US4] Extend `src/test/java/com/kibo/reservation/it/HoldRaceIT.java` with the
  **cancel vs expire** and **cancel vs confirm** races (×raceRuns): exactly one final state, the
  invariant holds, and no double release.
  - _Status: done in `ConfirmCancelRaceIT` (cancel vs confirm) and `HoldRaceIT` (cancel vs expire)._

### Implementation for User Story 4

- [X] T051 [US4] Implement `HoldService.cancel(holdId, customerId)` in
  `src/main/java/com/kibo/reservation/application/HoldService.java`:
  - calls `HoldTransitions.cancelAndRelease` via `TransactionRetry`;
  - on 0 rows, classifies: idempotent for CANCELLED; INVALID_STATE_TRANSITION for CONFIRMED;
    HOLD_EXPIRED for EXPIRED or overdue (settle via `expireAndRelease`); HOLD_NOT_FOUND for a missing hold
    or wrong customer;
  - logs the outcome (FR-031).
  - _Status: done: same settle-on-contact as T041; `TransactionRetry` still deferred._
- [X] T052 [US4] Add `POST /api/v1/holds/{holdId}/cancel` to `src/main/java/com/kibo/reservation/api/HoldController.java`.

**Checkpoint**: The full lifecycle works and all race pairs are proven.

---

## Phase 7: User Story 5 - Retrieve the State of a Hold (Priority: P3)

**Goal**: Customers see their own hold's current state. Overdue ACTIVE holds show as EXPIRED; other
customers' holds look not found.

**Independent Test**: Create holds in each state. GET returns the correct status and timestamps; another
customer gets 404.

- [X] T053 [P] [US5] Unit test `HoldService.getHold` in `src/test/java/com/kibo/reservation/application/HoldServiceGetTest.java`:
  - effective EXPIRED for overdue ACTIVE;
  - wrong customer, unknown id, or malformed id → HOLD_NOT_FOUND.
- [X] T054 [P] [US5] `@WebMvcTest` for `GET /api/v1/holds/{holdId}` in
  `src/test/java/com/kibo/reservation/api/HoldControllerGetTest.java`: 200 body schema, 400 missing
  `X-Customer-Id`, 404.
- [X] T055 [US5] Implement `HoldService.getHold(holdId, customerId)` (`@Transactional(readOnly = true)`,
  read-only, no settling) in `src/main/java/com/kibo/reservation/application/HoldService.java`.
- [X] T056 [US5] Add `GET /api/v1/holds/{holdId}` to `src/main/java/com/kibo/reservation/api/HoldController.java`.

**Checkpoint**: Hold status is visible to its owner.

---

## Phase 8: User Story 6 - Browse Drops and View a Drop (Priority: P3) — includes Redis cache

**Goal**: Customers list and view drops with availability no more than 5s stale. The service still works
when Redis is down.

**Independent Test**: With seeded drops, list and view them. After a hold, availability updates within 5s.
With Redis stopped, reads still succeed from MySQL.

### Tests for User Story 6

- [X] T057 [P] [US6] Unit test `DropQueryService` in `src/test/java/com/kibo/reservation/application/DropQueryServiceTest.java`:
  - snapshot mapping;
  - `availabilityStatus` computed with the current clock after the cache read (an UPCOMING snapshot flips
    to OPEN at `startsAt`);
  - unknown id → DROP_NOT_FOUND.
- [X] T058 [P] [US6] `@WebMvcTest` for `GET /api/v1/drops` and `GET /api/v1/drops/{dropId}` in
  `src/test/java/com/kibo/reservation/api/DropControllerTest.java`: `DropResponse` schema; 404 DROP_NOT_FOUND.
- [X] T059 [P] [US6] Unit test `LoggingCacheErrorHandler` (get, put and evict errors are swallowed and
  logged) and `DropCacheEvictionListener` (evicts on CREATED, CANCELLED and EXPIRED, not on CONFIRMED) in
  `src/test/java/com/kibo/reservation/cache/DropCacheTest.java`.
  - _Status: done as `cache/DropCacheTest`, `cache/DropCacheEvictionListenerTest` and `cache/CacheIsNotUsedForDecisionsTest`. There is no `LoggingCacheErrorHandler`: `DropCache` itself catches, logs and bypasses (see T063)._

### Implementation for User Story 6

- [X] T060 [P] [US6] Create the `DropResponse` record and the `DropSnapshot` cache record in
  `src/main/java/com/kibo/reservation/api/dto/DropResponse.java` and
  `src/main/java/com/kibo/reservation/application/DropSnapshot.java`.
- [X] T061 [US6] Implement `DropQueryService` in `src/main/java/com/kibo/reservation/application/DropQueryService.java`:
  - `listDrops()` uses `@Cacheable("drops:all")`; `getDrop(id)` uses `@Cacheable("drops")`;
  - read-only transactions; returns snapshots;
  - computes `DropAvailabilityStatus` outside the cache.
  - _Status: done as explicit cache-aside in `DropQueryService` (not `@Cacheable`): the database transaction opens only on a miss, never around Redis calls, and unknown drops are never cached. `availabilityStatus` is still computed outside the cache._
- [X] T062 [US6] Implement `GET /api/v1/drops` and `GET /api/v1/drops/{dropId}` in
  `src/main/java/com/kibo/reservation/api/DropController.java`.
- [X] T063 [US6] Implement `RedisCacheConfig` in `src/main/java/com/kibo/reservation/cache/RedisCacheConfig.java`:
  - `RedisCacheManager` with JSON serialization and TTL `kibo.cache.drop-ttl` (3s);
  - Lettuce connect timeout ~500ms and command timeout ~200ms;
  - registers `LoggingCacheErrorHandler` (implemented in
    `src/main/java/com/kibo/reservation/cache/LoggingCacheErrorHandler.java`).
  - _Status: done differently: no `RedisCacheManager`/`RedisCacheConfig`. `cache/DropCache` uses `StringRedisTemplate` with typed Jackson JSON (no class names stored), TTL `kibo.cache.drop-ttl`, and fails open with a bypass window of `max(5 s, TTL)`. Timeouts (connect 500 ms, command 200 ms) are set in `application.yml`. Rationale: `docs/caching-redis.md`._
- [X] T064 [US6] Implement `DropCacheEvictionListener` in
  `src/main/java/com/kibo/reservation/cache/DropCacheEvictionListener.java`.
  It uses `@TransactionalEventListener(phase = AFTER_COMMIT)` on `HoldLifecycleEvent` and evicts
  `drops::{dropId}` and `drops:all` for CREATED, CANCELLED and EXPIRED.
  - _Status: done in `cache/DropCacheEvictionListener` (AFTER_COMMIT on `HoldLifecycleEvent`; evicts `kibo:drops:<id>` and `kibo:drops:all` for created, cancelled and expired; not for confirmed)._

**Checkpoint**: All six stories work. Reads are cached and the cache is bypassed safely on failure.

---

## Phase 9: Lifecycle Event Publishing (RabbitMQ) — cross-cutting

**Purpose**: Publish HOLD_* events after commit via an isolated adapter (plan, research.md §8, contracts/events.md)

- [X] T065 [P] Unit test `RabbitHoldEventPublisher` in
  `src/test/java/com/kibo/reservation/messaging/RabbitHoldEventPublisherTest.java` with a mocked
  `RabbitTemplate`:
  - correct exchange, routing key and JSON payload;
  - a broker exception is swallowed and logged at WARN with the payload.
- [X] T066 [P] Create `RabbitMessagingConfig` in `src/main/java/com/kibo/reservation/messaging/RabbitMessagingConfig.java`:
  - topic exchange `kibo.holds` and durable queue `kibo.holds.audit` bound with `hold.#` (names from
    `kibo.messaging.*`);
  - Jackson message converter; publisher confirms on.
- [X] T067 [P] Create the `HoldEventMessage` record per contracts/events.md in
  `src/main/java/com/kibo/reservation/messaging/HoldEventMessage.java`.
- [X] T068 Implement `RabbitHoldEventPublisher` in
  `src/main/java/com/kibo/reservation/messaging/RabbitHoldEventPublisher.java`:
  - `@TransactionalEventListener(phase = AFTER_COMMIT)` with `@Async("eventPublisherExecutor")`;
  - maps the event to the message; routing key `hold.created|confirmed|cancelled|expired`;
  - catches and logs failures (at-most-once).
- [X] T069 Create `AsyncConfig` in `src/main/java/com/kibo/reservation/config/AsyncConfig.java`: bounded
  `eventPublisherExecutor` (core 2, max 4, queue 1000, discard-and-log rejection policy).

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: Resilience proof, end-to-end validation, documentation (Constitution XVI, XVII)

- [ ] T070 [P] `InfrastructureDownIT` in `src/test/java/com/kibo/reservation/it/InfrastructureDownIT.java`:
  - Redis and RabbitMQ point at closed ports;
  - place, confirm, cancel and expire stay correct with the invariant holding;
  - drop reads are served from MySQL; readiness is UP (SC-005).
- [ ] T071 [P] `EndToEndIT` in `src/test/java/com/kibo/reservation/it/EndToEndIT.java`, with MySQL, Redis and
  RabbitMQ containers:
  - REST happy path across all 6 endpoints;
  - the drop cache entry is evicted after a hold;
  - a `hold.created` message arrives on `kibo.holds.audit`.
- [ ] T072 [P] Write `README.md`: overview, stack, how to run (compose), how to test, API summary, links
  to `docs/` and the spec folder.
- [ ] T073 [P] Write `docs/architecture.md` (layers, package map, request flow; embed or link `docs/HLD.md`)
  and fix the `\n` label breaks in `docs/HLD.md` to `<br/>` for renderer compatibility.
- [ ] T074 [P] Write `docs/concurrency-and-transactions.md` from research.md §2–§5: the conditional
  updates, guarded transitions, transaction-boundary table, isolation level, retries and the
  duplicate-key race.
- [X] T075 [P] Write `docs/caching-redis.md` (cache-aside, TTL, after-commit eviction, staleness bound,
  failure behavior, why Redis isn't authoritative) and `docs/messaging-rabbitmq.md` (topology,
  after-commit at-most-once trade-off, outbox as future work).
  - _Status: PARTIAL: `docs/caching-redis.md` is written (cache-aside, TTL, after-commit eviction, staleness bound, failure behavior, why Redis is not authoritative). `docs/messaging-rabbitmq.md` remains for Phase 9._
- [ ] T076 [P] Write `docs/expiration.md` (job design, multi-instance safety, 10s bound, read-time EXPIRED,
  settle-on-contact) and `docs/testing-strategy.md` (unit vs IT split, test-to-SC mapping, how to run full
  repetition counts).
- [ ] T077 [P] Write `docs/future-improvements.md` (research.md §14) and the ADRs in `docs/decisions/`:
  - `0001-conditional-update-for-inventory.md`;
  - `0002-guarded-state-transitions.md`;
  - `0003-scheduled-expiration-without-locks.md`;
  - `0004-redis-cache-aside.md`;
  - `0005-after-commit-event-publishing.md`.
  Each records the decision, the alternatives considered and why they were rejected.
- [ ] T078 Review all AI-generated code against the constitution checklist (no JVM locks, no read-then-write
  on inventory, no hardcoded hosts or credentials, DTOs only at the API edge). Inspect the SQL in debug
  logs for the place, cancel and expire paths, and record findings in `docs/ai-review-log.md`
  (Constitution XVI).
- [ ] T079 Run `mvn verify -Dkibo.it.runs=100 -Dkibo.it.raceRuns=1000`, then run every quickstart.md
  scenario (including `docker compose stop redis rabbitmq`), and record the results in
  `docs/testing-strategy.md`.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup and BLOCKS all stories. T017 needs T015, T016 and T018.
  T014 needs T012 and T013. T025 needs T007.
- **US1 (Phase 3)**: after Foundational. It's the MVP.
- **US2 (Phase 4)**: after Foundational. Needs holds to exist (via `HoldCreation` or test fixtures);
  independently testable.
- **US3 (Phase 5)**: after Foundational. `HoldRaceIT` (T045) also needs US2's confirm.
- **US4 (Phase 6)**: after Foundational. T050 extends T045, so it runs after US3.
- **US5 (Phase 7)**: after Foundational; independent.
- **US6 (Phase 8)**: after Foundational. T064 relies on `HoldLifecycleEvent` (T018) only.
- **Messaging (Phase 9)**: after Foundational (T018); independent of the stories.
- **Polish (Phase 10)**: after all desired stories and Phase 9.

### Within Each Story

Tests (fail first) → DTOs/mappers → transactional component → service → controller → logging → checkpoint.

### Parallel Opportunities

- Setup: T003–T006.
- Foundational: T008–T013, T015, T016, T018–T024 and T026, all [P] in different files.
- After Foundational, US1, US5, US6 and Phase 9 can proceed in parallel. US2 → US3 → US4 is the only
  sequenced chain, because of the shared `HoldRaceIT` and `HoldService` methods.
- Docs tasks T072–T077 can run in parallel.

## Parallel Example: User Story 1

```bash
# Tests together (all different files):
Task: "T027 HoldServiceCreateTest"   Task: "T028 HoldControllerCreateTest"
Task: "T029 ConcurrentReservationIT" Task: "T030 IdempotentHoldIT"
# Then DTOs together:
Task: "T031 CreateHoldRequest + HoldResponse"   Task: "T032 HoldMapper"
```

## Implementation Strategy

### MVP First (Day 1 morning–afternoon)

1. Phase 1 + Phase 2.
2. Phase 3 (US1). **STOP and validate**: T029 and T030 green, and the invariant holds. This is the core
   no-oversell guarantee.

### Incremental Delivery (rest of Day 1 → Day 2)

3. US2 confirm → US3 expiration (race proven) → US4 cancel (all races proven).
4. US5 GET hold → US6 drops + Redis.
5. Phase 9 RabbitMQ.
6. Phase 10 resilience ITs, docs and the AI review.

If time runs short, cut from the end: Phase 9 and the EndToEndIT are the most deferrable. Never cut
T029, T045, T050 or the docs on concurrency (Constitution scope rule).

## Notes

- [P] = different files, no dependencies on incomplete tasks; [USx] = traceability to spec stories.
- Commit after each task or logical group; stop at each checkpoint to validate the story independently.

---

## Phase 11: Convergence

- [X] T080 CRITICAL: Route every production hold state change through the `HoldStatus` transition table (e.g. check `canTransitionTo(target)` in `HoldService.transitionInTransaction` and `HoldExpirationService.expireInTransaction`, and drive the 0-row loser classification from it); today `canTransitionTo` is referenced only by `HoldStatusTest` and the rules are hard-coded in guarded JPQL and `==` checks per Constitution VI (contradicts)
- [X] T081 Make idempotency keys and customer references case-sensitive: add `V2__` migration changing `holds.customer_id` and `holds.request_key` to a binary collation (`utf8mb4_bin`), make `HoldService.replayOf` also verify the existing hold's owner, and add an IT proving customers `bob`/`Bob` and keys `k1`/`K1` are independent (today `utf8mb4_0900_ai_ci` makes `Bob`+`k1` replay `bob`'s hold, leaking its id) per FR-009, FR-022a (contradicts)
- [X] T082 Log every rejected request with code and context (customerId, dropId, quantity where available): API-edge rejections in `GlobalExceptionHandler.handleExceptionInternal` (missing/invalid headers, invalid body, type mismatch), malformed-holdId 404s from `HoldController.parseHoldId`, 503s (currently a bare WARN), the IDEMPOTENCY_KEY_CONFLICT thrown from the duplicate-key catch in `HoldService.placeHold`, and add dropId/quantity to confirm/cancel rejection logs per FR-031 (partial)
- [X] T083 Make every error response satisfy the `Problem` schema: set `code` for all Spring MVC errors (404 unknown route, 405, 406, 415) in `GlobalExceptionHandler`, and fill `errors[{field,message}]` for bean-validation failures (`MethodArgumentNotValidException`, `HandlerMethodValidationException`); cover both in the T026 test per SC-006, contracts/openapi.yaml `Problem` (partial)
- [ ] T084 Scope T070 `InfrastructureDownIT` to the uncovered gap: `RabbitOutageIT`/`AbstractMySqlIT` already cover sequential place/confirm/cancel/expire and readiness with Redis and RabbitMQ unreachable, but every concurrency/race IT runs with `kibo.messaging.enabled=false`; run the 200-way placement and a confirm/cancel-vs-expire race with messaging enabled and both brokers on closed ports, asserting the invariant per SC-005, FR-027 (partial)
- [ ] T085 Reconcile the unrequested `AuditEventConsumer` (enabled by default, drains `kibo.holds.audit` so quickstart §6's "see messages in the queue" shows an empty queue): either default `kibo.messaging.audit-consumer-enabled` to false, or record it in plan.md Complexity Tracking (which says "no consumer in scope") and update quickstart §6 per plan: Complexity Tracking, Constitution XV (unrequested)
- [ ] T086 Add `docs/` (`HLD.md`, `caching-redis.md`, `messaging-rabbitmq.md`) to version control — it is untracked while research.md §7/§8 link to it — and update the stale T075 status note per Constitution XVII (partial)
- [ ] T087 Prevent expiry-sweep starvation: `HoldExpirationService.expireOverdue` re-reads page 0 and stops when a whole page fails, so ≥ batch-size persistently failing holds block newer overdue holds forever; page by keyset `(expiresAt, id)` past ids that failed in this sweep, with a unit test per FR-018 (partial)
- [ ] T088 Align IT repetition defaults with the tasks: `ConfirmCancelRaceIT` should use `kibo.it.raceRuns` (default 200) instead of `kibo.it.runs` (default 10), and `ConcurrentReservationIT` should default `kibo.it.runs` to 20 per T029, T050, SC-001, SC-002 (partial)
- [X] T089 Add `DEFAULT 4` to `drops.max_per_hold` in the `V2__` migration (V1 declares it without a default) per data-model.md, T007 (partial)
- [ ] T090 Validate `kibo.hold.duration` is positive at startup in `KiboProperties` (zero/negative currently passes and every placement fails with a 500) per FR-008, Constitution XI (missing)
- [ ] T091 Verify how a lost DB connection during commit surfaces (likely `TransactionSystemException` → 500) and map connectivity-caused `TransactionSystemException`, `QueryTimeoutException` and `TransientDataAccessResourceException` to 503 SERVICE_UNAVAILABLE, with a test per FR-029, Edge Cases (partial)
- [ ] T092 Make `DataSeeder` safe when several instances start on an empty DB (count-then-insert can double-seed), e.g. insert-if-absent by a unique seed name, or document it as single-instance only per Constitution IX, T021 (partial)
- [ ] T093 Record the as-built substitutions in tasks.md status notes: `ErrorCode` in `domain/exception` (T013); `ApiHeaders` + `@Pattern` instead of `HeaderValidation` (T022); `HoldResponse.from` instead of `HoldMapper` (T032); `HoldService.placeInTransaction` via `TransactionTemplate` instead of `HoldCreation` (T033); manual `ObjectMapper` instead of a Jackson message converter (T066); and close T017/T024 as superseded by `UnitRelease` + existing tests (their "events not published yet" note is stale) per tasks.md traceability (partial)
- [ ] T094 Fix local-run docs: quickstart.md §1 omits `REDIS_PASSWORD` and `RABBITMQ_PORT`; `application-local.yml` (IDE run against compose) cannot work because compose exposes no MySQL/Redis ports and `.env` hosts are service names — document a ports override or remove the profile per quickstart.md, plan: configuration (partial)

---

## Phase 12: Convergence

- [ ] T095 Reconcile the unrequested root `AI-USAGE.md` with T078: T078 specifies `docs/ai-review-log.md` (constitution-checklist review plus the SQL inspected for place/cancel/expire); either make `AI-USAGE.md` that record (and point T078 and the README at it) or link it from `docs/ai-review-log.md`, so there is one authoritative AI-review record per Constitution XVI, T078 (unrequested)
