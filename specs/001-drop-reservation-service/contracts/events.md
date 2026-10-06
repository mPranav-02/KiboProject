# Event Contract: Hold Lifecycle (RabbitMQ)

**Delivery**: at-most-once, published asynchronously **after** the database commit (see
[research.md §8](../research.md)). Consumers MUST NOT treat events as the source of truth. MySQL is
authoritative. Events can be lost on broker outage or a crash between commit and publish. A transactional
outbox is the planned future improvement.

## Topology

| Item | Value |
|------|-------|
| Exchange | `kibo.holds` (topic, durable) |
| Routing keys | `hold.created`, `hold.confirmed`, `hold.cancelled`, `hold.expired` |
| Demo queue | `kibo.holds.audit` (durable), bound with `hold.#` |
| Content type | `application/json`, persistent delivery mode |

Names are configurable (`kibo.messaging.exchange`, `kibo.messaging.audit-queue`).

## Message schema (all four types)

```json
{
  "eventId": "6f1c2a4e-1d7b-4a43-9a51-2c0e8f6b9d10",
  "eventType": "HOLD_CREATED",
  "occurredAt": "2026-10-06T10:00:00.123456Z",
  "holdId": "0b7f3f0e-5a1e-4d6a-8b0a-4c1f2a9e7d33",
  "dropId": 1,
  "customerId": "cust-42",
  "quantity": 2,
  "status": "ACTIVE",
  "expiresAt": "2026-10-06T10:05:00.123456Z"
}
```

| Field | Notes |
|-------|-------|
| `eventId` | UUID, unique per event; consumers de-duplicate on it |
| `eventType` | `HOLD_CREATED` \| `HOLD_CONFIRMED` \| `HOLD_CANCELLED` \| `HOLD_EXPIRED` |
| `occurredAt` | commit-time timestamp from the service clock (UTC) |
| `status` | hold status after the change (`ACTIVE`, `CONFIRMED`, `CANCELLED`, `EXPIRED`) |
| `expiresAt` | hold expiry time |

## Emission rules

- Emitted **only** when a transition actually happened: the 1-row path in research.md §3.
- Idempotent replays and rejected requests emit **no** event.
- Ordering is not guaranteed across holds, and not strictly guaranteed for one hold (async publisher).
  Consumers should use `status` and `occurredAt`.
