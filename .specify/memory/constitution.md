<!--
Sync Impact Report
==================
Version change: (unratified template) → 1.0.0
Modified principles: N/A (initial ratification; all template placeholders replaced)
Added principles:
  I. Correctness Over Premature Optimization
  II. Never Oversell Inventory
  III. MySQL Is the Source of Truth
  IV. Transactional, Concurrency-Safe Inventory Mutations
  V. Redis Is Only a Cache
  VI. Explicit, Valid Domain State Transitions
  VII. Hold Lifecycle Rules
  VIII. Exactly-Once Inventory Return
  IX. No JVM-Local Synchronization for Distributed Correctness
  X. Business Logic Separated From Controllers and Infrastructure
  XI. Sensible REST Semantics and Structured Errors
  XII. Tests Target Concurrency, Transitions, Expiration, and Failure
  XIII. Unit/Service Tests Without Live Infrastructure
  XIV. No Hardcoded Hosts or Credentials
  XV. Simplicity Over Architectural Complexity
  XVI. AI-Generated Code Is Reviewed and Independently Validated
  XVII. Architectural Decisions Are Documented
Added sections: Scope & Technical Constraints; Development Workflow & Quality Gates; Governance
Removed sections: none
Templates requiring updates:
  ✅ .specify/templates/plan-template.md — "Constitution Check" gate reads this file at runtime; no edit needed
  ✅ .specify/templates/spec-template.md — no constitution-specific references; no edit needed
  ✅ .specify/templates/tasks-template.md — no constitution-specific references; no edit needed
Follow-up TODOs: none
-->

# KIBO Limited Drop Reservation Service Constitution

## Core Principles

### I. Correctness Over Premature Optimization

Correct inventory and reservation behavior MUST take priority over throughput, latency, or
resource efficiency. Optimizations MUST NOT be introduced unless a measured or clearly stated
need exists, and they MUST NOT weaken any guarantee in this constitution.

*Rationale*: A fast system that oversells a limited drop has failed at its only job.

### II. Never Oversell Inventory (NON-NEGOTIABLE)

The system MUST never grant more units than exist. At all times, for every item, available
quantity MUST be ≥ 0, and units held by ACTIVE holds plus units in CONFIRMED holds MUST NOT
exceed total stock. This invariant MUST hold under concurrent requests, retries, multiple
application instances, and partial failures.

*Rationale*: Overselling a limited drop is the defining failure of this service.

### III. MySQL Is the Source of Truth

MySQL MUST be the authoritative store for inventory quantities and reservation (hold) state.
Every decision to grant, confirm, cancel, or expire a hold MUST be made against MySQL data
inside a MySQL transaction. No other component's view of inventory or holds is authoritative.

*Rationale*: One durable, transactional authority removes ambiguity about what is true.

### IV. Transactional, Concurrency-Safe Inventory Mutations

Every inventory mutation MUST use a transactional, concurrency-safe database operation,
for example an atomic conditional update (`UPDATE ... SET available = available - ?
WHERE id = ? AND available >= ?` with an affected-row check), row-level locking
(`SELECT ... FOR UPDATE`), or optimistic versioning with bounded retry.
Unguarded read-then-write sequences on inventory are forbidden. A hold's state change and its
matching inventory change MUST commit or roll back together in one transaction.

*Rationale*: Without database-level atomicity, concurrent requests can both see "1 left".

### V. Redis Is Only a Cache

Redis MAY be used only to cache data for performance, for example availability reads. Redis
MUST NOT be required for inventory correctness: it MUST NOT hold authoritative counters, and
Redis-based locks MUST NOT be the mechanism that prevents overselling. If Redis is unavailable,
stale, or flushed, the system MUST remain correct; it MAY degrade in performance. Cached values
MUST NOT be used to decide whether a hold can be granted.

*Rationale*: A cache that can silently diverge from the source of truth cannot guard invariants.

### VI. Explicit, Valid Domain State Transitions

Hold state MUST be modeled as an explicit, finite set of states with an explicit transition
table in the domain layer. Every state change MUST go through that model. Invalid transitions
MUST be rejected and reported, never ignored or silently coerced. Persisted transitions MUST be
guarded by the expected current state (for example `WHERE status = 'ACTIVE'`) so that
concurrent competing transitions produce exactly one winner.

*Rationale*: Implicit state changes are where races and double-processing hide.

### VII. Hold Lifecycle Rules (NON-NEGOTIABLE)

An ACTIVE hold MAY transition only to CONFIRMED, CANCELLED, or EXPIRED. CONFIRMED, CANCELLED,
and EXPIRED are terminal and MUST NOT transition to any other state. A hold MUST NOT be
confirmed once its expiry time has passed, even if it has not yet been processed as EXPIRED.

*Rationale*: A small, closed lifecycle is easy to reason about, test, and verify.

### VIII. Exactly-Once Inventory Return

When a hold is CANCELLED or EXPIRED, its units MUST be returned to available inventory exactly
once, never zero times and never twice. The return MUST happen in the same transaction as the
guarded ACTIVE → CANCELLED/EXPIRED transition, so repeated cancel calls, concurrent
cancel-versus-expire races, and re-run expiration jobs cannot release units twice. CONFIRMED
holds MUST NOT return inventory.

*Rationale*: Double release creates phantom stock and leads directly to overselling.

### IX. No JVM-Local Synchronization for Distributed Correctness

Correctness MUST NOT depend on JVM-local mechanisms such as `synchronized`, `java.util.concurrent`
locks, in-memory maps, or single-instance assumptions. The service MUST remain correct when
several instances run concurrently against the same database, including any scheduled
expiration processing.

*Rationale*: In-process locks protect nothing once a second instance is started.

### X. Business Logic Separated From Controllers and Infrastructure

Reservation rules, state transitions, and invariants MUST live in domain/service code.
Controllers MUST only handle HTTP mapping, input validation, and response shaping.
Persistence and caching MUST sit behind clear boundaries so that business logic can be tested
without HTTP, MySQL, or Redis.

*Rationale*: Separation keeps rules testable and prevents correctness logic leaking into adapters.

### XI. Sensible REST Semantics and Structured Errors

REST endpoints MUST use resource-oriented paths and appropriate HTTP methods and status codes,
for example 201 for creation, 400 for invalid input, 404 for unknown resources, and 409 for
insufficient inventory or invalid state transitions. All error responses MUST use one
consistent, structured, machine-readable body (at minimum: an error code, a human-readable
message, and request context where useful). Internal exceptions and stack traces MUST NOT leak
to clients.

*Rationale*: Predictable contracts make the API easy to consume, test, and review.

### XII. Tests Target Concurrency, Transitions, Expiration, and Failure

The test suite MUST prioritize:

- **Concurrency**: many parallel reservation attempts against limited stock MUST never oversell.
- **State transitions**: every valid transition succeeds and every invalid one is rejected.
- **Expiration**: holds expire correctly, release inventory exactly once, and cannot be
  confirmed after expiry.
- **Failure scenarios**: transaction rollback, Redis unavailability, duplicate or retried
  requests, and competing confirm/cancel/expire operations.

Happy-path-only coverage is insufficient for any feature touching inventory or hold state.

*Rationale*: The hard bugs in this domain only appear under contention, timing, and failure.

### XIII. Unit/Service Tests Without Live Infrastructure

Unit and service-layer tests MUST run without live MySQL, Redis, or network services, using
fakes, mocks, or in-memory substitutes behind the infrastructure boundaries. Tests that verify
database-level concurrency guarantees MUST be integration tests against real MySQL (for example
via Testcontainers), and MUST be clearly separated so the unit suite runs fast and standalone.

*Rationale*: Fast, hermetic unit tests support iteration; real-database tests prove the locking.

### XIV. No Hardcoded Hosts or Credentials

Configuration MUST NOT contain hardcoded infrastructure hosts, ports for shared environments,
usernames, passwords, or secrets. These values MUST come from environment variables or
externalized configuration. Committed example files MAY contain clearly marked local-only
placeholders.

*Rationale*: Hardcoded infrastructure details leak secrets and break portability.

### XV. Simplicity Over Architectural Complexity

The simplest design that satisfies these principles MUST be preferred. Additional services,
message brokers, event sourcing, CQRS, distributed locks, or extra abstraction layers MUST NOT
be introduced without a documented, concrete need (see Principle XVII and the plan's Complexity
Tracking table).

*Rationale*: Each extra moving part is another place for correctness to break and must be explained.

### XVI. AI-Generated Code Is Reviewed and Independently Validated

All AI-generated code, tests, and documentation MUST be reviewed by a human before being
accepted. Correctness claims, especially concurrency and transaction behavior, MUST be
independently validated by running tests and inspecting the actual SQL/transaction boundaries,
not assumed from the generated output.

*Rationale*: AI output can look plausible while being subtly wrong exactly where it matters most.

### XVII. Architectural Decisions Are Documented

Important architectural decisions MUST be recorded with context, the chosen approach, the
alternatives considered, and why those alternatives were rejected (for example: pessimistic vs.
optimistic locking, how expiration is scheduled, and why Redis is not the inventory authority).
Records MUST live in the repository (for example `docs/decisions/` or a decisions section in
the README).

*Rationale*: Reviewers of a take-home assess reasoning as much as code.

## Scope & Technical Constraints

- **Context**: This is a two-day take-home assignment. Scope MUST remain focused on the core
  reservation problem: limited inventory, creating holds, and confirming, cancelling, and
  expiring them safely under concurrency.
- **Out of scope unless a spec explicitly requires it**: payments, full user/account management,
  production-grade authentication, UIs, multi-region deployment, and non-essential
  infrastructure.
- **Technology**: JVM-based service; MySQL as the system of record; Redis optional and
  cache-only. All technology choices MUST stay consistent with Principles III, IV, V, and IX.
- **Time budget**: When time is short, correctness, tests, and documented decisions (Principles
  II, IV, VIII, XII, XVII) MUST be delivered before any optional feature or optimization.

## Development Workflow & Quality Gates

- Every feature MUST follow the Spec Kit flow: specify → (clarify) → plan → tasks → implement.
- Each implementation plan's **Constitution Check** MUST evaluate the design against every
  principle above before research and again after design. Any deviation MUST be justified in
  the plan's **Complexity Tracking** table, or the design MUST be changed.
- A change touching inventory or hold state is done only when:
  - the concurrency, transition, expiration, and failure tests from Principle XII exist and pass;
  - the unit suite passes without live infrastructure (Principle XIII);
  - any new architectural decision is recorded (Principle XVII);
  - AI-generated parts have been reviewed and validated (Principle XVI).
- Configuration and committed files MUST be checked for hosts and credentials before commit
  (Principle XIV).

## Governance

- This constitution supersedes all other project practices and guidance. Where a spec, plan,
  task, or code conflicts with it, the constitution wins until it is formally amended.
- **Amendments**: Changes MUST be made by editing this file with a short rationale, updating the
  version and Last Amended date, and checking dependent Spec Kit artifacts (specs, plans, tasks)
  for consistency.
- **Versioning**: Semantic versioning applies. MAJOR for removing or redefining a principle in a
  backward-incompatible way; MINOR for adding a principle or section or materially expanding
  guidance; PATCH for clarifications and wording fixes.
- **Compliance review**: Every plan and every review of code touching inventory, holds, or
  configuration MUST verify compliance with this constitution. Unjustified violations block
  acceptance.

**Version**: 1.0.0 | **Ratified**: 2026-10-06 | **Last Amended**: 2026-10-06
