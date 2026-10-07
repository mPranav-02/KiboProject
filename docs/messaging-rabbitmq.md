# RabbitMQ lifecycle events

RabbitMQ carries **after-the-fact notifications** about holds. It is never consulted to decide anything.
MySQL is the source of truth; inventory, holds and expiry work identically with the broker up, down or
removed (Constitution V and XV, FR-027).

## What is published

| Event | Raised when | `status` in the message | Routing key |
|-------|-------------|-------------------------|-------------|
| `HOLD_CREATED`   | a hold was created (units taken)         | `ACTIVE`    | `hold.created` |
| `HOLD_CONFIRMED` | ACTIVE → CONFIRMED (units stay consumed) | `CONFIRMED` | `hold.confirmed` |
| `HOLD_CANCELLED` | ACTIVE → CANCELLED (units returned)      | `CANCELLED` | `hold.cancelled` |
| `HOLD_EXPIRED`   | ACTIVE → EXPIRED (units returned)        | `EXPIRED`   | `hold.expired` |

An event exists **only** for a transition that really happened (the one-row path of the guarded update).
Idempotent replays, repeated confirms/cancels and rejected requests publish nothing. The JSON schema is in
`specs/001-drop-reservation-service/contracts/events.md`.

## Topology

| Item | Default | Setting |
|------|---------|---------|
| Exchange (topic, durable) | `kibo.holds` | `kibo.messaging.exchange` / `KIBO_MESSAGING_EXCHANGE` |
| Demo queue (durable), bound with `hold.#` | `kibo.holds.audit` | `kibo.messaging.audit-queue` / `KIBO_MESSAGING_AUDIT_QUEUE` |
| Publish events at all | `true` | `kibo.messaging.enabled` / `KIBO_MESSAGING_ENABLED` |
| Run the demo consumer | `true` | `kibo.messaging.audit-consumer-enabled` / `KIBO_MESSAGING_AUDIT_CONSUMER_ENABLED` |

The broker address and credentials come only from the environment: `RABBITMQ_HOST`, `RABBITMQ_PORT`,
`RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` (no defaults, nothing hard-coded). Messages are JSON, persistent,
with `messageId` = `eventId` and the event type as AMQP `type`. The exchange, queue and binding are declared
by Spring's `RabbitAdmin` whenever it opens a connection, so they appear on their own when the broker comes
up (or after it was wiped).

With the compose stack, open the management UI (rabbitmq:4-management) to watch the queue, and the app log
shows one `AUDIT HOLD_... eventId=... holdId=...` line per event from the demo consumer.

## How it is wired (and kept apart from business logic)

```
HoldService / HoldExpirationService          (application layer)
   └─ publishEvent(HoldLifecycleEvent)        in-process Spring event, inside the DB transaction
        ├─ DropCacheEvictionListener          AFTER_COMMIT
        └─ RabbitHoldEventPublisher           AFTER_COMMIT + @Async("eventPublisherExecutor")
             └─ RabbitTemplate → exchange kibo.holds → queue kibo.holds.audit → AuditEventConsumer
```

The business code only knows the in-process event. Nothing in `application`, `domain`, `repository` or
`api` references the `messaging` package or the AMQP client (`MessagingIsolationTest` enforces it).

* **After commit**: a rolled-back transaction raises no callback, so it can never publish an event for
  something that did not happen.
* **Off the request path**: publishing runs on a bounded pool (2–4 threads, queue 1000). When the queue is
  full the event is dropped and logged; it is never run on the request thread.
* **Publisher confirms**: the publisher waits (5 s, on its own thread) for the broker's ack, so "accepted"
  is distinguished from "lost".

## Delivery guarantee: at-most-once

Publishing happens after the commit, as a separate step. Therefore an event is **lost** when:

1. the broker is down, unreachable or hung (connect timeout 1 s, confirm timeout 5 s, both on a pool thread);
2. the broker nacks or never confirms the publish;
3. the process crashes between the commit and the publish;
4. the publisher queue is full.

Every lost event except 3 is logged at **WARN** with its full payload (`Lifecycle event NOT published
...`), so it can be replayed by hand if anyone cares. There is **no retry** and **no outbox**.
Publishing *before* commit would instead risk events for changes that were rolled back, which is worse for
a notification stream. Order is not guaranteed across holds, nor strictly for one hold; consumers use
`status` and `occurredAt`, and de-duplicate on `eventId`.

## What happens when RabbitMQ is unavailable

| Aspect | Behaviour | Verified by |
|--------|-----------|-------------|
| Application start | Starts normally; the topology is declared when the broker first answers. The demo consumer just retries its connection in the background. | `RabbitOutageIT` (publisher + consumer enabled, broker unreachable) |
| Place / confirm / cancel / expire / reads | Unchanged: same results, same inventory, no added latency (publishing is async). | `RabbitOutageIT` (refused), `RabbitBrokerHangIT` (paused broker: every operation < 2 s) |
| Inventory and hold state | Correct in MySQL; the invariant holds. | `assertInventoryInvariant` in both ITs |
| Expiration job | Independent of messaging; keeps expiring and returning units. | `RabbitOutageIT` |
| Events that could not be sent | Lost (see above), one WARN each. | `RabbitOutageIT` |
| Readiness / liveness | Stay UP: readiness depends on MySQL only. | `RabbitOutageIT` |
| `/actuator/health` (overall) | Shows `rabbit: DOWN` and reports DOWN/503, i.e. visible to operators, without taking the service out of rotation. | observed in `RabbitOutageIT` |
| Broker returns | New events flow again with no restart; events lost meanwhile are not replayed. | `RabbitBrokerHangIT` |

To remove messaging entirely set `KIBO_MESSAGING_ENABLED=false`: the publisher, topology and consumer are
not created and the service behaves exactly the same.

## Why no outbox (and what it would take)

A transactional outbox (write an `outbox_events` row in the same transaction, have a relay publish and mark
it sent) gives at-least-once delivery that survives a broker outage or crash, with consumers de-duplicating
on `eventId`. It needs a table, a relay job (safe on N instances) and its own concurrency tests. For this
take-home the events are notifications and the Constitution prefers the simpler design (XV), so the outbox
is documented as the **future improvement** rather than built. The `HoldLifecycleEvent` seam makes it a
local change: replace the `@TransactionalEventListener` with a write to the outbox table.

## Tests

* Unit: `HoldEventMessageTest` (schema, routing keys), `RabbitHoldEventPublisherTest` (exchange, key,
  persistent JSON, failures/nacks/no-confirm swallowed and logged, AFTER_COMMIT + `@Async`),
  `AuditEventConsumerTest`, `AsyncConfigTest` (bounded, drops when full), `MessagingIsolationTest`.
* Integration against a real broker (`rabbitmq:4`): `RabbitEventsIT` (every transition, schema, no events for
  replays/rejections/rollback, topology, configurable names), `AuditConsumerIT`, `RabbitBrokerHangIT`;
  broker unreachable: `RabbitOutageIT`. By default every other IT runs with messaging off
  (`application-test.yml`).
