# Data Model: Limited Drop Reservation Service

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

MySQL 8.4 (InnoDB) is the system of record. Schema is owned by Flyway (`V1__create_drops_and_holds.sql`).
All timestamps are `DATETIME(6)` in UTC.

The brief's minimum model is extended with these fields, each needed by a spec requirement:

| Added field | Why |
|-------------|-----|
| `drops.description` | Viewing a drop shows its description (FR-002) |
| `drops.max_per_hold` | Per-hold maximum (FR-005, FR-010) |
| `holds.request_key` | Required idempotency key (FR-009) |
| `holds.resolved_at` | Time the hold reached a final state (FR-022) |

## Entity: Drop (`drops`)

| Column | Type | Null | Rules |
|--------|------|------|-------|
| `id` | BIGINT AUTO_INCREMENT | no | PK |
| `name` | VARCHAR(200) | no | non-blank |
| `description` | VARCHAR(2000) | yes | |
| `total_quantity` | INT | no | `> 0`; fixed after provisioning (FR-003) |
| `available_quantity` | INT | no | `0 ≤ available ≤ total` (CHECK); changed only by guarded atomic updates |
| `max_per_hold` | INT | no | `≥ 1`; default 4 |
| `starts_at` | DATETIME(6) | no | release time; holds rejected before it (FR-007) |
| `created_at` | DATETIME(6) | no | |
| `updated_at` | DATETIME(6) | no | bumped on every inventory change |

Constraints:

- `CHECK (total_quantity > 0)`
- `CHECK (available_quantity >= 0 AND available_quantity <= total_quantity)`
- `CHECK (max_per_hold >= 1)`

**Derived, not stored**: `availabilityStatus` = `UPCOMING` if `now < starts_at`, else `SOLD_OUT` if
`available_quantity = 0`, else `OPEN`.

## Entity: Hold (`holds`)

| Column | Type | Null | Rules |
|--------|------|------|-------|
| `id` | CHAR(36) | no | PK; random UUID (hard to guess) |
| `drop_id` | BIGINT | no | FK → `drops.id` |
| `customer_id` | VARCHAR(64) | no | from `X-Customer-Id`; all hold operations must match it (FR-022a) |
| `request_key` | VARCHAR(64) | no | from `Idempotency-Key`; required (FR-009) |
| `quantity` | INT | no | `1 ≤ quantity ≤ drop.max_per_hold` (CHECK `quantity >= 1`; max checked in service) |
| `status` | VARCHAR(16) | no | `ACTIVE`, `CONFIRMED`, `CANCELLED`, `EXPIRED` (CHECK) |
| `expires_at` | DATETIME(6) | no | `created_at + hold duration` (default 5 min) |
| `resolved_at` | DATETIME(6) | yes | set when the hold reaches a final state |
| `created_at` | DATETIME(6) | no | |
| `updated_at` | DATETIME(6) | no | |

Constraints and indexes:

- `UNIQUE uk_holds_customer_request (customer_id, request_key)`: idempotency, including concurrent duplicates.
- `INDEX ix_holds_status_expires (status, expires_at)`: expiry sweep.
- `INDEX ix_holds_drop (drop_id)`: FK and invariant checks.
- `CHECK (quantity >= 1)`
- `CHECK (status IN ('ACTIVE','CONFIRMED','CANCELLED','EXPIRED'))`

**Derived for reads**: `effectiveStatus` = `EXPIRED` if `status = ACTIVE AND now >= expires_at`, else
`status` (FR-019).

## Relationships

- Drop 1 — * Hold. A hold belongs to exactly one drop and one customer (`customer_id` is an opaque
  reference, not a table).

## Hold state machine

```text
               confirm (now < expires_at)
         ┌──────────────────────────────► CONFIRMED   (units stay consumed)
         │
 ACTIVE ─┼── cancel  (now < expires_at) ─► CANCELLED   (+quantity returned, once)
         │
         └── expire  (now ≥ expires_at) ─► EXPIRED     (+quantity returned, once)

 CONFIRMED, CANCELLED, EXPIRED are final: no outgoing transitions.
```

| Current \ Requested | CONFIRM | CANCEL | EXPIRE (job) |
|---------------------|---------|--------|--------------|
| ACTIVE, not overdue | → CONFIRMED | → CANCELLED, return units | no-op (not due) |
| ACTIVE, overdue | 409 `HOLD_EXPIRED` (+ settle to EXPIRED) | 409 `HOLD_EXPIRED` (+ settle) | → EXPIRED, return units |
| CONFIRMED | 200 idempotent | 409 `INVALID_STATE_TRANSITION` | no-op |
| CANCELLED | 409 `INVALID_STATE_TRANSITION` | 200 idempotent | no-op |
| EXPIRED | 409 `HOLD_EXPIRED` | 409 `HOLD_EXPIRED` | no-op |

All transitions use guarded updates (`WHERE status='ACTIVE' ...`, see research.md §3), so concurrent
competitors produce exactly one winner.

## Invariants (asserted in every integration test)

1. **No oversell**: for every drop,
   `total_quantity − available_quantity = SUM(holds.quantity WHERE status IN ('ACTIVE','CONFIRMED'))`.
2. `0 ≤ available_quantity ≤ total_quantity` (also enforced by CHECK).
3. Every hold has exactly one status, and final states never change.
4. Each CANCELLED or EXPIRED hold returned its units exactly once (follows from 1 + 3).
5. At most one hold per `(customer_id, request_key)`.

## Validation rules (application layer → 400 `VALIDATION_ERROR`)

- `quantity`: integer, `1 ≤ quantity ≤ drop.max_per_hold`. The upper bound is checked after the drop is
  loaded; over the limit is a 400.
- `X-Customer-Id`: required on all hold endpoints, 1–64 characters, `[A-Za-z0-9._:-]`.
- `Idempotency-Key`: required on hold creation, 1–64 characters, same character set.
- Path ids: drop id is a positive long; hold id is a UUID. A malformed hold id returns 404 `HOLD_NOT_FOUND`
  (no existence oracle).

## Lifecycle events (published after commit; see [contracts/events.md](./contracts/events.md))

`HOLD_CREATED`, `HOLD_CONFIRMED`, `HOLD_CANCELLED`, `HOLD_EXPIRED`. These are not persisted in v1 (no
outbox).
