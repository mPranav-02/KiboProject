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

- [ ] T001 Create Maven project `pom.xml`:
  - Java 21; Spring Boot 3.5.x parent.
  - Starters: web, validation, data-jpa, data-redis, cache, amqp, actuator.
  - Libraries: flyway-core, flyway-mysql, mysql-connector-j.
  - Test dependencies: spring-boot-starter-test, spring-boot-testcontainers, testcontainers mysql, junit-jupiter.
  - Plugins: Surefire runs `*Test`; Failsafe runs `*IT`.
- [ ] T002 Create application entry point `src/main/java/com/kibo/reservation/KiboReservationApplication.java`
  with `@EnableScheduling` and `@EnableAsync`.
- [ ] T003 [P] Create `src/main/resources/application.yml`:
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
- [ ] T004 [P] Create `.env.example` (clearly marked local-only placeholder values for every variable in
  T003, plus `MYSQL_ROOT_PASSWORD`) and `.gitignore` (ignore `.env`, `target/`, `.idea/`).
- [ ] T005 [P] Create multi-stage `Dockerfile` (maven:3.9-eclipse-temurin-21 build → eclipse-temurin:21-jre
  runtime, non-root user, exposes 8080).
- [ ] T006 [P] Create `docker-compose.yml`:
  - Services: `app`, `mysql` (8.4), `redis` (7), `rabbitmq` (management image, ports 5672/15672).
  - Each service has a healthcheck; `app` uses `depends_on: condition: service_healthy`.
  - `env_file: .env`, a named volume for MySQL, and no hardcoded credentials.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema, domain model, transactional primitives, error handling and test harness that every
story depends on

**⚠️ CRITICAL**: No user story work begins until this phase is complete

- [ ] T007 Create Flyway migration `src/main/resources/db/migration/V1__create_drops_and_holds.sql`:
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
- [ ] T008 [P] Create `HoldStatus` enum with an explicit transition table (ACTIVE → {CONFIRMED, CANCELLED,
  EXPIRED}; final states have none) and `canTransitionTo(target)`/`isFinal()` in
  `src/main/java/com/kibo/reservation/domain/HoldStatus.java`.
- [ ] T009 [P] Create `DropAvailabilityStatus` enum with
  `static of(startsAt, availableQuantity, now)` → UPCOMING if `now < startsAt`, else SOLD_OUT if
  `available == 0`, else OPEN, in `src/main/java/com/kibo/reservation/domain/DropAvailabilityStatus.java`.
- [ ] T010 [P] Create the JPA entity `Drop` (fields per data-model.md; no setters for quantities) in
  `src/main/java/com/kibo/reservation/domain/Drop.java`.
- [ ] T011 [P] Create the JPA entity `Hold` (UUID id; `effectiveStatus(now)` returns EXPIRED when
  `status == ACTIVE && !now.isBefore(expiresAt)`) in `src/main/java/com/kibo/reservation/domain/Hold.java`.
- [ ] T012 [P] Create domain exceptions, each carrying an `ErrorCode`, in
  `src/main/java/com/kibo/reservation/domain/exception/`: `DomainException` (base),
  `DropNotFoundException`, `HoldNotFoundException`, `DropNotReleasedException`,
  `InsufficientInventoryException` (with availableQuantity), `HoldExpiredException`,
  `InvalidStateTransitionException` (with currentStatus), `IdempotencyKeyConflictException`,
  `InventoryInvariantViolationException`.
- [ ] T013 [P] Create the `ErrorCode` enum (VALIDATION_ERROR, DROP_NOT_FOUND, HOLD_NOT_FOUND,
  DROP_NOT_RELEASED, INSUFFICIENT_INVENTORY, HOLD_EXPIRED, INVALID_STATE_TRANSITION,
  IDEMPOTENCY_KEY_CONFLICT, SERVICE_UNAVAILABLE, INTERNAL_ERROR), each with an HTTP status, in
  `src/main/java/com/kibo/reservation/exception/ErrorCode.java`.
- [ ] T014 Create `GlobalExceptionHandler` (`@RestControllerAdvice`) in
  `src/main/java/com/kibo/reservation/exception/GlobalExceptionHandler.java`:
  - Returns RFC 7807 `ProblemDetail` with `code` and `timestamp`, plus `currentStatus`,
    `availableQuantity` and `errors[]` where relevant.
  - Maps bean-validation, missing-header and type-mismatch errors to 400.
  - Maps DB connectivity and exhausted lock retries to 503.
  - Maps anything else to 500 with no stack trace.
- [ ] T015 [P] Create `DropRepository` in `src/main/java/com/kibo/reservation/repository/DropRepository.java`.
  Both methods are `@Modifying(clearAutomatically = true, flushAutomatically = true)` and return the
  affected-row count:
  - `reserveUnits(id, qty, now)`:
    `UPDATE Drop d SET d.availableQuantity = d.availableQuantity - :qty, d.updatedAt = :now WHERE d.id = :id AND d.availableQuantity >= :qty AND d.startsAt <= :now`.
  - `releaseUnits(id, qty, now)`:
    `... + :qty ... WHERE d.id = :id AND d.availableQuantity + :qty <= d.totalQuantity`.
- [ ] T016 [P] Create `HoldRepository` in `src/main/java/com/kibo/reservation/repository/HoldRepository.java`:
  - `findByCustomerIdAndRequestKey`.
  - `findOverdueActiveIds(now, Pageable)`: `status = ACTIVE AND expiresAt <= :now ORDER BY expiresAt`.
  - Guarded `@Modifying` transitions, each returning the affected-row count:
    - `confirm(id, customerId, now)` and `cancel(id, customerId, now)`:
      `WHERE status = 'ACTIVE' AND expiresAt > :now`.
    - `expire(id, now)`: `WHERE status = 'ACTIVE' AND expiresAt <= :now`.
    - All three set `resolvedAt` and `updatedAt`.
- [ ] T017 Create `HoldTransitions` (`@Transactional`) in
  `src/main/java/com/kibo/reservation/application/HoldTransitions.java`:
  - `expireAndRelease(holdId, now)`: guarded expire; release units only if 1 row changed; throw
    `InventoryInvariantViolationException` if the release affects 0 rows.
  - `cancelAndRelease(holdId, customerId, now)`: same rule.
  - Each publishes a `HoldLifecycleEvent` only on the 1-row path. Shared by US2, US3 and US4.
- [ ] T018 [P] Create the `HoldLifecycleEvent` record (eventId, type HOLD_CREATED|CONFIRMED|CANCELLED|EXPIRED,
  occurredAt, holdId, dropId, customerId, quantity, status, expiresAt) in
  `src/main/java/com/kibo/reservation/domain/event/HoldLifecycleEvent.java`.
- [ ] T019 [P] Create config classes in `src/main/java/com/kibo/reservation/config/`:
  - `ClockConfig.java`: UTC `Clock` bean.
  - `KiboProperties.java`: `@ConfigurationProperties` for hold duration, default max-per-hold,
    expiration interval and batch size, cache TTL, seed enabled.
- [ ] T020 [P] Create `TransactionRetry` in `src/main/java/com/kibo/reservation/application/TransactionRetry.java`:
  - Retries `CannotAcquireLockException` and `PessimisticLockingFailureException` up to 3 attempts with
    jittered backoff.
  - Invoked outside the transactional proxy.
  - Throws `ServiceUnavailableException` when attempts are exhausted.
- [ ] T021 [P] Create `DataSeeder` (`ApplicationRunner`) in `src/main/java/com/kibo/reservation/config/DataSeeder.java`:
  - Only if the drops table is empty AND `kibo.seed.enabled`.
  - Inserts 4 drops relative to now: open 50 units, open 5 units, open 1 unit ("last unit"), upcoming
    (+10 min) 20 units.
- [ ] T022 [P] Create request-header validation helpers in
  `src/main/java/com/kibo/reservation/api/HeaderValidation.java`: `X-Customer-Id` and `Idempotency-Key`
  must be 1–64 chars matching `^[A-Za-z0-9._:-]+$`, otherwise VALIDATION_ERROR.
- [ ] T023 [P] Unit test the transition table, every (from, to) pair, in
  `src/test/java/com/kibo/reservation/domain/HoldStatusTest.java`. Also unit test
  `DropAvailabilityStatus.of` and `Hold.effectiveStatus` boundaries (exactly at `startsAt` and at
  `expiresAt`) in `src/test/java/com/kibo/reservation/domain/DomainStatusTest.java`.
- [ ] T024 [P] Unit test `HoldTransitions` in `src/test/java/com/kibo/reservation/application/HoldTransitionsTest.java`
  with mocked repositories:
  - release happens only when the guarded update returns 1;
  - a 0-row update releases nothing and publishes nothing;
  - a 0-row release throws the invariant exception.
- [ ] T025 Create the Testcontainers base class `AbstractMySqlIT` in
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

- [ ] T027 [P] [US1] Unit test hold placement in `src/test/java/com/kibo/reservation/application/HoldServiceCreateTest.java`
  (Mockito, fixed Clock). Cases:
  - success (status ACTIVE, `expiresAt = now + duration`);
  - quantity 0, or above `maxPerHold` → VALIDATION_ERROR;
  - drop not found; not yet released; insufficient inventory (with availableQuantity);
  - replay with the same key and details returns the original hold and does no decrement;
  - same key with a different drop or quantity → IDEMPOTENCY_KEY_CONFLICT;
  - 0-row decrement that finds the key on re-check → replay;
  - unique-key violation on insert → replay of the existing hold.
- [ ] T028 [P] [US1] `@WebMvcTest` for `POST /api/v1/drops/{dropId}/holds` in
  `src/test/java/com/kibo/reservation/api/HoldControllerCreateTest.java`:
  - 201 with `Location`; 200 on replay;
  - 400 when `X-Customer-Id` or `Idempotency-Key` is missing or invalid, or the body is invalid;
  - 404 DROP_NOT_FOUND;
  - 409 for INSUFFICIENT_INVENTORY, DROP_NOT_RELEASED and IDEMPOTENCY_KEY_CONFLICT;
  - response body matches the `HoldResponse` schema in contracts/openapi.yaml.
- [ ] T029 [P] [US1] `ConcurrentReservationIT` in `src/test/java/com/kibo/reservation/it/ConcurrentReservationIT.java`:
  - 200 threads behind a `CountDownLatch` start gate; drop of 50 units.
  - Asserts 50 successes, 150 INSUFFICIENT_INVENTORY, available 0, and the invariant.
  - Repeats `-Dkibo.it.runs` times (default 20).
  - Mixed-quantity case: 5 units left, requests for 3, 3 and 2. Each is fully granted or fully rejected,
    and no more than 5 units are granted in total.
- [ ] T030 [P] [US1] `IdempotentHoldIT` in `src/test/java/com/kibo/reservation/it/IdempotentHoldIT.java`:
  10 concurrent requests with the same customer and key give exactly 1 hold row and one decrement (SC-003).

### Implementation for User Story 1

- [ ] T031 [P] [US1] Create the `CreateHoldRequest` record (`@NotNull @Min(1) Integer quantity`) and the
  `HoldResponse` record (id, dropId, quantity, status, createdAt, expiresAt, resolvedAt) in
  `src/main/java/com/kibo/reservation/api/dto/`.
- [ ] T032 [P] [US1] Create `HoldMapper` in `src/main/java/com/kibo/reservation/api/dto/HoldMapper.java`.
  It maps Hold → HoldResponse using `effectiveStatus(now)` (FR-019) and never exposes the entity.
- [ ] T033 [US1] Implement `HoldCreation` (`@Transactional`) in
  `src/main/java/com/kibo/reservation/application/HoldCreation.java`, as research.md §2 orders it:
  1. Key lookup.
  2. `reserveUnits`.
  3. On 1 row: insert the ACTIVE hold with `saveAndFlush` and publish HOLD_CREATED.
  4. On 0 rows: re-check the key, then classify as DROP_NOT_FOUND, DROP_NOT_RELEASED or
     INSUFFICIENT_INVENTORY.
  Validate quantity against `drop.maxPerHold`.
- [ ] T034 [US1] Implement `HoldService.placeHold(dropId, customerId, requestKey, quantity)` in
  `src/main/java/com/kibo/reservation/application/HoldService.java`. It is non-transactional:
  - wraps `HoldCreation` in `TransactionRetry`;
  - on `DataIntegrityViolationException` for `uk_holds_customer_request`, reads and returns the existing
    hold (replay), or IDEMPOTENCY_KEY_CONFLICT if the details differ;
  - returns a result flag saying whether the hold was created or replayed.
- [ ] T035 [US1] Implement `POST /api/v1/drops/{dropId}/holds` in
  `src/main/java/com/kibo/reservation/api/HoldController.java`:
  - validates headers with `HeaderValidation`;
  - returns 201 + `Location: /api/v1/holds/{id}` when created, 200 when replayed.
- [ ] T036 [US1] Add structured logging (holdId, dropId, customerId, quantity, outcome/code) for every
  hold placement and rejection in `src/main/java/com/kibo/reservation/application/HoldService.java` (FR-031).

**Checkpoint**: US1 works alone. T029 and T030 are green, and holds can be placed through curl.

---

## Phase 4: User Story 2 - Confirm an Active Hold (Priority: P1)

**Goal**: Customers confirm ACTIVE holds before expiry. A repeated confirm is idempotent; an expired or
invalid confirm is rejected.

**Independent Test**: Create a hold and confirm it: status CONFIRMED, units stay consumed. Confirm again:
200 with no change. Confirm an overdue hold: 409 HOLD_EXPIRED and the hold settles to EXPIRED.

### Tests for User Story 2

- [ ] T037 [P] [US2] Unit test confirm in `src/test/java/com/kibo/reservation/application/HoldServiceConfirmTest.java`:
  - ACTIVE → CONFIRMED;
  - repeat on CONFIRMED → idempotent success with no update;
  - overdue ACTIVE → HOLD_EXPIRED and `expireAndRelease` invoked;
  - EXPIRED → HOLD_EXPIRED; CANCELLED → INVALID_STATE_TRANSITION (with currentStatus);
  - wrong customer or unknown id → HOLD_NOT_FOUND.
- [ ] T038 [P] [US2] `@WebMvcTest` for `POST /api/v1/holds/{holdId}/confirm` in
  `src/test/java/com/kibo/reservation/api/HoldControllerConfirmTest.java`: 200, 404 (including a malformed
  UUID), 409 HOLD_EXPIRED and 409 INVALID_STATE_TRANSITION with `currentStatus`.
- [ ] T039 [P] [US2] `ConfirmIT` in `src/test/java/com/kibo/reservation/it/ConfirmIT.java`:
  - confirm keeps units consumed and the invariant holds;
  - confirm at exactly `expiresAt` is rejected (Clock fixed at the boundary).

### Implementation for User Story 2

- [ ] T040 [US2] Implement `HoldConfirmation` (`@Transactional`) in
  `src/main/java/com/kibo/reservation/application/HoldConfirmation.java`:
  - guarded `confirm`; on 1 row, publish HOLD_CONFIRMED;
  - on 0 rows, load and classify: HOLD_NOT_FOUND for a missing hold or wrong customer; idempotent for
    CONFIRMED; HOLD_EXPIRED for EXPIRED or overdue; INVALID_STATE_TRANSITION otherwise.
- [ ] T041 [US2] Implement `HoldService.confirm(holdId, customerId)` in
  `src/main/java/com/kibo/reservation/application/HoldService.java`:
  - uses `TransactionRetry`;
  - on an overdue hold, calls `HoldTransitions.expireAndRelease` in its own transaction before throwing
    HOLD_EXPIRED;
  - logs the outcome (FR-031).
- [ ] T042 [US2] Add `POST /api/v1/holds/{holdId}/confirm` to
  `src/main/java/com/kibo/reservation/api/HoldController.java` (a malformed UUID gives 404 HOLD_NOT_FOUND).

**Checkpoint**: US1 and US2 both work independently.

---

## Phase 5: User Story 3 - Automatic Expiration of Unconfirmed Holds (Priority: P2)

**Goal**: Overdue ACTIVE holds expire without any customer action and release units exactly once, within 10s.

**Independent Test**: Create a hold with a short duration and wait. It becomes EXPIRED, availability is
restored once, and confirming it is rejected. Concurrent or repeated sweeps never double-release (SC-004).

### Tests for User Story 3

- [ ] T043 [P] [US3] Unit test `HoldExpirationService` in
  `src/test/java/com/kibo/reservation/application/HoldExpirationServiceTest.java`:
  - batches until a short page is returned;
  - per-hold failure is logged and the batch continues;
  - a 0-row expire is a no-op.
- [ ] T044 [P] [US3] `ExpirationIT` in `src/test/java/com/kibo/reservation/it/ExpirationIT.java`:
  - overdue holds become EXPIRED and units are restored;
  - 3 concurrent sweeper threads plus repeated sweeps release each hold exactly once (invariant);
  - a hold overdue at "restart" is expired on the first sweep;
  - confirm after expiry → HOLD_EXPIRED.
- [ ] T045 [US3] Create `HoldRaceIT` in `src/test/java/com/kibo/reservation/it/HoldRaceIT.java` with
  **confirm vs expire**, run `-Dkibo.it.raceRuns` times (default 200):
  - the two operations use clocks either side of `expiresAt`;
  - asserts exactly one final state and `available == total − confirmed` (SC-002).

### Implementation for User Story 3

- [ ] T046 [US3] Implement `HoldExpirationService.expireOverdue(now)` in
  `src/main/java/com/kibo/reservation/application/HoldExpirationService.java`:
  - pages `findOverdueActiveIds` by `kibo.expiration.batch-size`;
  - calls `HoldTransitions.expireAndRelease` per id (own transaction, via `TransactionRetry`);
  - loops while a page is full.
- [ ] T047 [US3] Implement `HoldExpirationJob` in
  `src/main/java/com/kibo/reservation/application/HoldExpirationJob.java`:
  - `@Scheduled(fixedDelayString = "${kibo.expiration.interval}")` calling `expireOverdue(clock.instant())`;
  - logs a summary (expired count, duration) and each expiry (FR-031);
  - no JVM locks or ShedLock.

**Checkpoint**: Expiry runs on its own. The confirm-vs-expire race is proven.

---

## Phase 6: User Story 4 - Cancel an Active Hold (Priority: P2)

**Goal**: Customers cancel ACTIVE holds, returning units exactly once. A repeated cancel is idempotent;
cancelling a confirmed or expired hold is rejected.

**Independent Test**: Cancel returns units once. Cancel again: 200 with no change. Cancel vs expire and
cancel vs confirm races each produce exactly one final state.

### Tests for User Story 4

- [ ] T048 [P] [US4] Unit test cancel in `src/test/java/com/kibo/reservation/application/HoldServiceCancelTest.java`:
  - ACTIVE → CANCELLED with release;
  - repeat on CANCELLED → idempotent with no release;
  - CONFIRMED → INVALID_STATE_TRANSITION;
  - EXPIRED or overdue → HOLD_EXPIRED and settle;
  - wrong customer → HOLD_NOT_FOUND.
- [ ] T049 [P] [US4] `@WebMvcTest` for `POST /api/v1/holds/{holdId}/cancel` in
  `src/test/java/com/kibo/reservation/api/HoldControllerCancelTest.java`: 200, 404, and 409 with
  `currentStatus`.
- [ ] T050 [US4] Extend `src/test/java/com/kibo/reservation/it/HoldRaceIT.java` with the
  **cancel vs expire** and **cancel vs confirm** races (×raceRuns): exactly one final state, the
  invariant holds, and no double release.

### Implementation for User Story 4

- [ ] T051 [US4] Implement `HoldService.cancel(holdId, customerId)` in
  `src/main/java/com/kibo/reservation/application/HoldService.java`:
  - calls `HoldTransitions.cancelAndRelease` via `TransactionRetry`;
  - on 0 rows, classifies: idempotent for CANCELLED; INVALID_STATE_TRANSITION for CONFIRMED;
    HOLD_EXPIRED for EXPIRED or overdue (settle via `expireAndRelease`); HOLD_NOT_FOUND for a missing hold
    or wrong customer;
  - logs the outcome (FR-031).
- [ ] T052 [US4] Add `POST /api/v1/holds/{holdId}/cancel` to `src/main/java/com/kibo/reservation/api/HoldController.java`.

**Checkpoint**: The full lifecycle works and all race pairs are proven.

---

## Phase 7: User Story 5 - Retrieve the State of a Hold (Priority: P3)

**Goal**: Customers see their own hold's current state. Overdue ACTIVE holds show as EXPIRED; other
customers' holds look not found.

**Independent Test**: Create holds in each state. GET returns the correct status and timestamps; another
customer gets 404.

- [ ] T053 [P] [US5] Unit test `HoldService.getHold` in `src/test/java/com/kibo/reservation/application/HoldServiceGetTest.java`:
  - effective EXPIRED for overdue ACTIVE;
  - wrong customer, unknown id, or malformed id → HOLD_NOT_FOUND.
- [ ] T054 [P] [US5] `@WebMvcTest` for `GET /api/v1/holds/{holdId}` in
  `src/test/java/com/kibo/reservation/api/HoldControllerGetTest.java`: 200 body schema, 400 missing
  `X-Customer-Id`, 404.
- [ ] T055 [US5] Implement `HoldService.getHold(holdId, customerId)` (`@Transactional(readOnly = true)`,
  read-only, no settling) in `src/main/java/com/kibo/reservation/application/HoldService.java`.
- [ ] T056 [US5] Add `GET /api/v1/holds/{holdId}` to `src/main/java/com/kibo/reservation/api/HoldController.java`.

**Checkpoint**: Hold status is visible to its owner.

---

## Phase 8: User Story 6 - Browse Drops and View a Drop (Priority: P3) — includes Redis cache

**Goal**: Customers list and view drops with availability no more than 5s stale. The service still works
when Redis is down.

**Independent Test**: With seeded drops, list and view them. After a hold, availability updates within 5s.
With Redis stopped, reads still succeed from MySQL.

### Tests for User Story 6

- [ ] T057 [P] [US6] Unit test `DropQueryService` in `src/test/java/com/kibo/reservation/application/DropQueryServiceTest.java`:
  - snapshot mapping;
  - `availabilityStatus` computed with the current clock after the cache read (an UPCOMING snapshot flips
    to OPEN at `startsAt`);
  - unknown id → DROP_NOT_FOUND.
- [ ] T058 [P] [US6] `@WebMvcTest` for `GET /api/v1/drops` and `GET /api/v1/drops/{dropId}` in
  `src/test/java/com/kibo/reservation/api/DropControllerTest.java`: `DropResponse` schema; 404 DROP_NOT_FOUND.
- [ ] T059 [P] [US6] Unit test `LoggingCacheErrorHandler` (get, put and evict errors are swallowed and
  logged) and `DropCacheEvictionListener` (evicts on CREATED, CANCELLED and EXPIRED, not on CONFIRMED) in
  `src/test/java/com/kibo/reservation/cache/DropCacheTest.java`.

### Implementation for User Story 6

- [ ] T060 [P] [US6] Create the `DropResponse` record and the `DropSnapshot` cache record in
  `src/main/java/com/kibo/reservation/api/dto/DropResponse.java` and
  `src/main/java/com/kibo/reservation/application/DropSnapshot.java`.
- [ ] T061 [US6] Implement `DropQueryService` in `src/main/java/com/kibo/reservation/application/DropQueryService.java`:
  - `listDrops()` uses `@Cacheable("drops:all")`; `getDrop(id)` uses `@Cacheable("drops")`;
  - read-only transactions; returns snapshots;
  - computes `DropAvailabilityStatus` outside the cache.
- [ ] T062 [US6] Implement `GET /api/v1/drops` and `GET /api/v1/drops/{dropId}` in
  `src/main/java/com/kibo/reservation/api/DropController.java`.
- [ ] T063 [US6] Implement `RedisCacheConfig` in `src/main/java/com/kibo/reservation/cache/RedisCacheConfig.java`:
  - `RedisCacheManager` with JSON serialization and TTL `kibo.cache.drop-ttl` (3s);
  - Lettuce connect timeout ~500ms and command timeout ~200ms;
  - registers `LoggingCacheErrorHandler` (implemented in
    `src/main/java/com/kibo/reservation/cache/LoggingCacheErrorHandler.java`).
- [ ] T064 [US6] Implement `DropCacheEvictionListener` in
  `src/main/java/com/kibo/reservation/cache/DropCacheEvictionListener.java`.
  It uses `@TransactionalEventListener(phase = AFTER_COMMIT)` on `HoldLifecycleEvent` and evicts
  `drops::{dropId}` and `drops:all` for CREATED, CANCELLED and EXPIRED.

**Checkpoint**: All six stories work. Reads are cached and the cache is bypassed safely on failure.

---

## Phase 9: Lifecycle Event Publishing (RabbitMQ) — cross-cutting

**Purpose**: Publish HOLD_* events after commit via an isolated adapter (plan, research.md §8, contracts/events.md)

- [ ] T065 [P] Unit test `RabbitHoldEventPublisher` in
  `src/test/java/com/kibo/reservation/messaging/RabbitHoldEventPublisherTest.java` with a mocked
  `RabbitTemplate`:
  - correct exchange, routing key and JSON payload;
  - a broker exception is swallowed and logged at WARN with the payload.
- [ ] T066 [P] Create `RabbitMessagingConfig` in `src/main/java/com/kibo/reservation/messaging/RabbitMessagingConfig.java`:
  - topic exchange `kibo.holds` and durable queue `kibo.holds.audit` bound with `hold.#` (names from
    `kibo.messaging.*`);
  - Jackson message converter; publisher confirms on.
- [ ] T067 [P] Create the `HoldEventMessage` record per contracts/events.md in
  `src/main/java/com/kibo/reservation/messaging/HoldEventMessage.java`.
- [ ] T068 Implement `RabbitHoldEventPublisher` in
  `src/main/java/com/kibo/reservation/messaging/RabbitHoldEventPublisher.java`:
  - `@TransactionalEventListener(phase = AFTER_COMMIT)` with `@Async("eventPublisherExecutor")`;
  - maps the event to the message; routing key `hold.created|confirmed|cancelled|expired`;
  - catches and logs failures (at-most-once).
- [ ] T069 Create `AsyncConfig` in `src/main/java/com/kibo/reservation/config/AsyncConfig.java`: bounded
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
- [ ] T075 [P] Write `docs/caching-redis.md` (cache-aside, TTL, after-commit eviction, staleness bound,
  failure behavior, why Redis isn't authoritative) and `docs/messaging-rabbitmq.md` (topology,
  after-commit at-most-once trade-off, outbox as future work).
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
