# Quickstart & Validation Guide: Limited Drop Reservation Service

How to run the service and prove it meets the spec. API details: [contracts/openapi.yaml](./contracts/openapi.yaml).
Data and state rules: [data-model.md](./data-model.md).

## Prerequisites

- Docker Desktop (or Docker Engine) with Compose v2.
- JDK 21 and Maven 3.9+, only needed to run tests or the app outside Docker.

## 1. Configure

```bash
cp .env.example .env        # local-only values; .env is gitignored
```

`.env` supplies `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `REDIS_HOST`,
`REDIS_PORT`, `RABBITMQ_HOST`, `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD`. Nothing is hardcoded in
`application.yml`.

## 2. Run everything

```bash
docker compose up --build -d
docker compose ps                                  # all services healthy
curl -s localhost:8080/actuator/health/readiness   # {"status":"UP"}
```

Startup seeds ~4 drops (two open, one with a single unit, one upcoming in 10 minutes). Flyway creates the
schema.

## 3. Happy-path validation (US1, US2, US5, US6)

```bash
curl -s localhost:8080/api/v1/drops | jq                      # list: availabilityStatus OPEN/UPCOMING
curl -s localhost:8080/api/v1/drops/1 | jq                    # view one drop

# Place a hold → 201, status ACTIVE, expiresAt ≈ now+5m
curl -si -X POST localhost:8080/api/v1/drops/1/holds \
  -H 'Content-Type: application/json' -H 'X-Customer-Id: alice' -H 'Idempotency-Key: k1' \
  -d '{"quantity":2}'

# Same request again → 200, same hold id, availability unchanged (SC-003)
# Confirm → 200 CONFIRMED; confirm again → 200 (idempotent)
curl -s -X POST localhost:8080/api/v1/holds/<holdId>/confirm -H 'X-Customer-Id: alice' | jq
# Cancel the confirmed hold → 409 INVALID_STATE_TRANSITION, currentStatus CONFIRMED
# GET the hold as another customer → 404 HOLD_NOT_FOUND
```

Expected: the drop's `availableQuantity` drops by 2 and stays down after the confirm.

## 4. Negative and edge validation

| Scenario | Expected |
|----------|----------|
| `quantity` 0, or above `maxPerHold` | 400 `VALIDATION_ERROR` |
| Missing `Idempotency-Key` or `X-Customer-Id` | 400 `VALIDATION_ERROR` |
| Hold on the upcoming drop | 409 `DROP_NOT_RELEASED` |
| Request more than available | 409 `INSUFFICIENT_INVENTORY` with `availableQuantity`; nothing consumed |
| Same key, different quantity | 409 `IDEMPOTENCY_KEY_CONFLICT` |
| Cancel an ACTIVE hold, then cancel again | 200 CANCELLED, then 200 (units returned once) |
| Unknown drop or hold id | 404 `DROP_NOT_FOUND` / `HOLD_NOT_FOUND` |

## 5. Expiration (US3)

Run with a short hold duration, e.g. `KIBO_HOLD_DURATION=PT20S` in `.env`, then `docker compose up -d`.

1. Place a hold and don't confirm it. Wait about 30s.
2. `GET /holds/{id}` returns `EXPIRED`, and the drop's availability is restored within 10s of `expiresAt`.
3. Confirming it now returns 409 `HOLD_EXPIRED`.

## 6. Infrastructure failure (SC-005)

```bash
docker compose stop redis rabbitmq
# Repeat sections 3 and 4: all results identical; app logs WARN for cache/publish failures
curl -s localhost:8080/actuator/health/readiness   # still UP (readiness depends on DB only)
docker compose start redis rabbitmq
```

To see events: the RabbitMQ management UI at http://localhost:15672 (credentials from `.env`) shows
messages in queue `kibo.holds.audit` ([contracts/events.md](./contracts/events.md)).

## 7. Automated tests

```bash
mvn test      # unit + controller slice tests; NO Docker/MySQL/Redis/RabbitMQ needed
mvn verify    # + Testcontainers integration tests (Docker required)
mvn verify -Dkibo.it.runs=100 -Dkibo.it.raceRuns=1000   # full SC-001 / SC-002 repetition counts
```

| Test | Proves |
|------|--------|
| `ConcurrentReservationIT` | SC-001: 200 parallel requests on 50 units give exactly 50 holds, never oversold |
| `HoldRaceIT` | SC-002: confirm/cancel vs expire races, one final state, units returned once |
| `IdempotentHoldIT` | SC-003: 10 concurrent same-key requests give one hold |
| `ExpirationIT` | SC-004: expiry within bound, multi-sweeper safety, no confirm after expiry |
| `InfrastructureDownIT` | SC-005: correct with Redis and RabbitMQ unreachable |
| `EndToEndIT` | REST flow, cache eviction after commit, event published |

Every IT asserts the invariant `total − available = Σ quantity(ACTIVE + CONFIRMED)` per drop.

## 8. Teardown

```bash
docker compose down -v
```
