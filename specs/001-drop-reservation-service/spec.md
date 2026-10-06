# Feature Specification: Limited Drop Reservation Service

**Feature Branch**: `001-drop-reservation-service` (spec directory; no git branch created, work is on `master`)

**Created**: 2026-10-06

**Status**: Draft

**Input**: User description: "Build a limited-availability 'drop' reservation service. A drop represents a fixed
quantity of scarce units that become available at a point in time (concert tickets, restaurant tables,
appointment slots, limited products). Customers must be able to view available drops, view a specific drop,
place a temporary hold on one or more units, confirm an active hold, cancel an active hold, have an unconfirmed
hold expire automatically, and retrieve the state of a hold. Concurrency is a first-class requirement: total
successfully allocated inventory must never exceed the drop's total inventory, and races between
cancellation/expiration, confirmation/expiration, and repeated requests must be handled correctly. The system
must remain correct if caching or messaging infrastructure is temporarily unavailable. Scope must fit a two-day
take-home. No technology is prescribed."

## Clarifications

### Session 2026-10-06

- Q: What happens when a confirm or cancel is repeated on a hold already in that final state? → A: It succeeds idempotently, returns the current hold, and changes nothing. A request targeting a different final state is still rejected.
- Q: Is there a per-customer limit on units per drop? → A: No. Only the per-hold maximum applies, and customers may place multiple holds.
- Q: When a hold passes its expiry time but hasn't been processed yet, how soon must its units become available to other customers? → A: Within 10 seconds, released by background expiry processing. New hold requests do not reclaim overdue holds themselves.
- Q: Must confirming, cancelling, or viewing a hold use the same customer reference that placed it? → A: Yes. A mismatched customer reference is answered as "hold not found", so the hold's existence isn't revealed.
- Q: Is the request key required or optional when placing a hold? → A: Required. A hold request without a request key is rejected as invalid input.
- Q: How much operational visibility should the service provide? → A: Structured logs for every hold state change and rejection, plus a basic health check. Metrics are out of scope.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Place a Hold Without Overselling (Priority: P1)

A customer who wants scarce units of a released drop asks to hold one or more of them. If enough units
are available, the customer receives a hold that reserves those units for a limited time and tells them
when it expires. If not enough units are available, the customer is told clearly that the request
cannot be fulfilled. No matter how many customers ask at the same moment, the drop never gives out more
units than it has.

**Why this priority**: Fair, correct allocation of scarce units is the core value of the service and the
defining risk (overselling). Every other story depends on holds existing.

**Independent Test**: With a pre-provisioned drop of known quantity, submit many simultaneous hold
requests and verify that the sum of granted units equals at most the drop's total, that every request
receives either a hold or a clear "insufficient availability" result, and that availability never goes
below zero.

**Acceptance Scenarios**:

1. **Given** a released drop with 10 units available, **When** a customer requests a hold for 3 units,
   **Then** a hold is created in ACTIVE state for 3 units with an expiry time, and the drop shows 7 units
   available.
2. **Given** a released drop with 2 units available, **When** a customer requests 3 units, **Then** the
   request is rejected with an "insufficient availability" result, no hold is created, and the drop still
   shows 2 units available (no partial allocation).
3. **Given** a released drop with 0 units available, **When** a customer requests 1 unit, **Then** the
   request is rejected as sold out.
4. **Given** a drop with 50 units and 200 customers each requesting 1 unit at the same moment, **When**
   all requests complete, **Then** exactly 50 holds are granted, 150 requests are rejected as
   insufficient availability, and available units equal 0.
5. **Given** a drop whose release time is in the future, **When** a customer requests a hold, **Then** the
   request is rejected with a "not yet released" result.
6. **Given** a request for 0 units, a negative quantity, more than the per-hold maximum, or no request
   key, **When** it is submitted, **Then** it is rejected as invalid input and inventory is unchanged.
7. **Given** a customer retries the same hold request (same request key) after a timeout, **When** the
   retry arrives, **Then** the customer receives the original hold and no additional units are consumed.

---

### User Story 2 - Confirm an Active Hold (Priority: P1)

A customer holding units decides to complete their reservation before the hold expires. Confirming the
hold permanently allocates the held units to them.

**Why this priority**: Without confirmation, no reservation is ever completed; it turns a temporary hold
into a lasting outcome.

**Independent Test**: Create a hold, confirm it before expiry, and verify the hold is CONFIRMED, the units
remain consumed, and further cancel or expire attempts do not return units.

**Acceptance Scenarios**:

1. **Given** an ACTIVE hold that has not expired, **When** the customer confirms it, **Then** the hold
   becomes CONFIRMED and its units stay permanently consumed.
2. **Given** a hold whose expiry time has passed (whether or not the system has yet processed the
   expiry), **When** the customer tries to confirm it, **Then** the request is rejected with a "hold
   expired" result and the hold ends in EXPIRED state.
3. **Given** a CANCELLED hold, **When** the customer tries to confirm it, **Then** the request is rejected
   as an invalid state transition.
4. **Given** an ACTIVE hold reaching its expiry time at the same moment a confirm request arrives,
   **When** both are processed, **Then** exactly one outcome wins: either the hold is CONFIRMED and units
   stay consumed, or it is EXPIRED and its units are returned exactly once; never both.
5. **Given** a CONFIRMED hold, **When** the same customer sends the confirm request again, **Then**
   the request succeeds idempotently: it returns the current CONFIRMED hold and changes nothing.

---

### User Story 3 - Automatic Expiration of Unconfirmed Holds (Priority: P2)

A customer places a hold but never confirms or cancels it. Once the hold's time limit passes, the hold
expires on its own and its units go back to the drop so other customers can get them.

**Why this priority**: Without expiration, abandoned holds would lock up scarce inventory permanently.
It is essential for fairness but builds on holds existing (Story 1).

**Independent Test**: Create a hold with a known expiry, wait past it without acting, and verify the hold
reports EXPIRED, the units are available again exactly once, and the hold cannot be confirmed.

**Acceptance Scenarios**:

1. **Given** an ACTIVE hold for 2 units whose expiry time passes with no action, **When** the expiry is
   processed, **Then** the hold becomes EXPIRED and the drop's available units increase by exactly 2.
2. **Given** an ACTIVE hold past its expiry time that has not yet been processed, **When** anyone
   retrieves the hold, **Then** it is reported as EXPIRED, not ACTIVE.
3. **Given** expiry processing runs more than once, or on more than one server, for the same hold,
   **When** all runs complete, **Then** the hold's units have been returned exactly once.
4. **Given** the drop is sold out and a hold expires, **When** expiry is processed, **Then** another
   customer can successfully hold the returned units.

---

### User Story 4 - Cancel an Active Hold (Priority: P2)

A customer changes their mind and releases their hold before it expires, returning the units so others
can get them.

**Why this priority**: Lets customers release units early and improves availability, but the system is
still correct without it because holds expire anyway.

**Independent Test**: Create a hold, cancel it, and verify the hold is CANCELLED and the units are
returned exactly once, including when cancel is repeated or races with expiry.

**Acceptance Scenarios**:

1. **Given** an ACTIVE hold for 3 units, **When** the customer cancels it, **Then** the hold becomes
   CANCELLED and the drop's available units increase by exactly 3.
2. **Given** a CONFIRMED hold, **When** the customer tries to cancel it, **Then** the request is rejected
   as an invalid state transition and no units are returned.
3. **Given** an EXPIRED hold (including one past expiry but not yet processed), **When** the customer
   tries to cancel it, **Then** the request is rejected with a "hold expired" result and no additional
   units are returned.
4. **Given** an ACTIVE hold reaching its expiry time at the same moment a cancel request arrives,
   **When** both are processed, **Then** the hold ends in exactly one of CANCELLED or EXPIRED and its
   units are returned exactly once.
5. **Given** a CANCELLED hold, **When** the same customer sends cancel again, **Then** the request succeeds
   idempotently: it returns the current CANCELLED hold and no additional units are returned.

---

### User Story 5 - Retrieve the State of a Hold (Priority: P3)

A customer checks the current state of a hold they placed: how many units, which drop, its status, and
when it expires or reached its final state.

**Why this priority**: Gives customers and clients confidence about outcomes (especially after timeouts),
but is not required for allocation correctness.

**Independent Test**: Create holds in each state and verify retrieval returns the correct status, quantity,
drop, and relevant timestamps.

**Acceptance Scenarios**:

1. **Given** an existing hold, **When** the customer retrieves it, **Then** they see its identifier,
   drop, quantity, status (ACTIVE, CONFIRMED, CANCELLED, or EXPIRED), creation time, and expiry time,
   plus the time it reached its final state if it has one.
2. **Given** a hold identifier that does not exist, **When** it is retrieved, **Then** a "not found"
   result is returned.
3. **Given** a hold placed by customer A, **When** customer B tries to retrieve, confirm, or cancel it,
   **Then** a "hold not found" result is returned and the hold is unchanged.

---

### User Story 6 - Browse Drops and View a Drop (Priority: P3)

A customer browses the available drops and opens one to see what it is, when it is released, how many
units it has in total, and how many are currently available.

**Why this priority**: The entry point to discovery, but allocation, confirmation, and expiry can be
fully tested against known drops without it.

**Independent Test**: With several pre-provisioned drops, list them and view each one; verify details
and availability match the drop's actual state.

**Acceptance Scenarios**:

1. **Given** several drops exist, **When** a customer lists drops, **Then** each drop is shown with its
   name, release time, total quantity, currently available quantity, and whether it is upcoming, open,
   or sold out.
2. **Given** an existing drop, **When** a customer views it, **Then** they see its full details including
   description and current availability.
3. **Given** a drop identifier that does not exist, **When** it is viewed, **Then** a "not found" result
   is returned.
4. **Given** units were just held, confirmed, cancelled, or expired, **When** a customer views the drop,
   **Then** the displayed availability reflects the change within 5 seconds.

---

### Edge Cases

- **Last-unit race**: Two customers request the final unit at the same instant: exactly one succeeds.
- **Multi-unit contention**: Requests for different quantities compete for the remaining units (for
  example, 5 left with requests for 3, 3, and 2 arriving together). Each request is granted fully or
  rejected fully, and the granted total never exceeds 5.
- **Expiry boundary**: A confirm arriving at or after the exact expiry time is rejected. The system's
  clock, not the client's, decides.
- **Confirm vs. expire, and cancel vs. expire**: Exactly one terminal state wins, and units are returned
  at most once (exactly once when the winner is CANCELLED or EXPIRED).
- **Cancel vs. confirm**: Arriving together, exactly one succeeds and the other is rejected as an invalid
  transition.
- **Duplicate and retried requests**: A repeated confirm or cancel that matches the hold's current final
  state succeeds without changing anything (FR-021). Hold placement retried with the same request key returns the
  original hold. The same request key reused with different details (drop or quantity) is rejected.
- **Repeated expiry processing**: Expiry processing that runs twice, or on several servers at once, does
  not return units twice.
- **Not-yet-released drop**: Holds are rejected until the release time. The drop is still visible as
  upcoming.
- **Unknown identifiers**: Unknown drop or hold identifiers return "not found" and change nothing.
- **Wrong customer**: Acting on another customer's hold returns "hold not found" and changes nothing.
- **Cache or messaging unavailable**: Allocation, confirmation, cancellation, and expiry stay correct.
  Only performance or display freshness may degrade.
- **Primary data store unavailable or a failure mid-operation**: The operation fails with a clear error,
  leaves no partial state (no units consumed without a hold, no hold without units), and can be retried
  safely.
- **Server restart**: Holds that expired while the service was down are expired and their units returned
  once the service is available again.

## Requirements *(mandatory)*

### Functional Requirements

**Drops**

- **FR-001**: System MUST let customers list drops, showing each drop's identifier, name, release time,
  total quantity, current available quantity, and availability status (UPCOMING, OPEN, or SOLD_OUT).
- **FR-002**: System MUST let customers view a single drop by identifier, including its description and
  the information in FR-001.
- **FR-003**: Each drop MUST have a fixed total quantity that does not change after the drop is provisioned.
- **FR-004**: Available quantity MUST always equal total quantity minus the units in ACTIVE holds and in
  CONFIRMED holds.

**Placing holds**

- **FR-005**: Customers MUST be able to request a hold on a specified number of units (at least 1, at most
  the per-hold maximum) of a released drop.
- **FR-006**: System MUST grant a hold only if enough units are available at the moment of the decision.
  Otherwise it MUST reject the request without granting any units (all-or-nothing).
- **FR-007**: System MUST reject hold requests for drops whose release time has not yet been reached.
- **FR-008**: A granted hold MUST start in ACTIVE state with an expiry time equal to its creation time plus
  the configured hold duration, and MUST reduce available quantity by its unit count.
- **FR-009**: Every hold request MUST include a client-supplied request key. Requests without one MUST be
  rejected as invalid input. A request key is unique per customer and is remembered for as long as the hold
  it created exists. A repeated request with the same key from the same customer MUST return the original
  hold (in whatever state it is now) without consuming more units. The same key with different request
  details (drop or quantity) MUST be rejected as a request key conflict.
- **FR-010**: The only per-customer quantity limit is the drop's per-hold maximum. A customer MAY
  place multiple holds on the same drop, and there is no cap on a customer's total units per drop.

**Hold lifecycle**

- **FR-011**: A hold MUST be in exactly one of ACTIVE, CONFIRMED, CANCELLED, or EXPIRED.
- **FR-012**: The only permitted transitions are ACTIVE → CONFIRMED, ACTIVE → CANCELLED, and
  ACTIVE → EXPIRED. CONFIRMED, CANCELLED, and EXPIRED are final.
- **FR-013**: System MUST reject any invalid transition, return a result that identifies the hold's current
  state, and leave inventory unchanged.
- **FR-014**: Customers MUST be able to confirm an ACTIVE hold before its expiry time. Confirmation MUST
  keep the held units permanently consumed.
- **FR-015**: System MUST reject confirmation of any hold whose expiry time has passed, even if its
  expiration has not yet been processed.
- **FR-016**: Customers MUST be able to cancel an ACTIVE hold before its expiry time. Cancelled units MUST
  be returned to available inventory.
- **FR-017**: System MUST reject cancellation of CONFIRMED or EXPIRED holds, including holds past their
  expiry time that are not yet processed.
- **FR-018**: System MUST automatically expire every ACTIVE hold whose expiry time has passed, without
  any customer action, and return its units no later than 10 seconds after its expiry time. Expiry is
  processed in the background. Placing a hold does not itself reclaim overdue holds, so until processed, an
  overdue hold's units remain unavailable to others for at most those 10 seconds.
- **FR-019**: Any hold past its expiry time and not confirmed MUST be reported as EXPIRED wherever it is
  shown.
- **FR-020**: Units of a CANCELLED or EXPIRED hold MUST be returned to available inventory exactly once.
  CONFIRMED holds MUST never return units.
- **FR-021**: A repeated confirm on an already-CONFIRMED hold, or a repeated cancel on an already-CANCELLED
  hold, MUST succeed idempotently: return the current hold state and change nothing. A request that targets
  a different final state (for example, confirm on a CANCELLED hold, or cancel on a CONFIRMED hold) MUST
  still be rejected per FR-013.
- **FR-022**: Customers MUST be able to retrieve a hold by identifier, seeing its drop, quantity, status,
  creation time, expiry time, and the time it reached a final state, if any.
- **FR-022a**: Retrieving, confirming, or cancelling a hold MUST require the same customer reference that
  placed it. A request with a different customer reference MUST receive a "hold not found" result and
  change nothing.

**Concurrency and integrity**

- **FR-023**: The total units in ACTIVE and CONFIRMED holds for a drop MUST never exceed the drop's total
  quantity, under any number of simultaneous requests and with any number of service instances running.
- **FR-024**: Available quantity MUST never be negative.
- **FR-025**: When a confirm, cancel, or expiry competes for the same hold, exactly one MUST take effect.
  The others MUST be rejected or have no effect.
- **FR-026**: Every operation that changes hold state or inventory MUST be all-or-nothing: either the hold
  state change and the matching inventory change both happen, or neither does.
- **FR-027**: Correctness of allocation, confirmation, cancellation, and expiry MUST NOT depend on caching
  or messaging infrastructure being available. If such infrastructure is unavailable, these operations
  MUST still behave correctly.
- **FR-028**: Availability shown to customers when browsing MAY lag behind the true value by up to 5
  seconds, but hold decisions MUST always use the true current availability.

**Client feedback**

- **FR-029**: Every failed request MUST return a clear, machine-readable reason that distinguishes at
  least: invalid input, drop not found, hold not found, drop not yet released, insufficient availability
  or sold out, hold expired, invalid state transition, request key conflict, and temporary service
  unavailability.
- **FR-030**: Every successful hold, confirm, or cancel MUST return the resulting hold state.

**Operability**

- **FR-031**: System MUST write a structured log entry for every hold state change (created, confirmed,
  cancelled, expired) and every rejected request. Each entry MUST include the hold identifier (where one
  exists), drop identifier, customer reference, unit count, and the outcome or rejection reason code.
- **FR-032**: System MUST provide a basic health check that reports whether the service can reach its
  primary data store.

### Key Entities

- **Drop**: A limited release of scarce units. Attributes: identifier, name, description, total quantity
  (fixed), release time, per-hold maximum. Derived values: available quantity and availability status
  (UPCOMING before the release time, OPEN while units remain, SOLD_OUT when none remain).
- **Hold**: A customer's time-limited claim on units of one drop. Attributes: identifier, drop, customer
  reference, quantity, status (ACTIVE, CONFIRMED, CANCELLED, or EXPIRED), created time, expiry time,
  final-state time, and the client request key used to place it. A hold belongs to exactly one drop and
  one customer.
- **Customer**: The person or client placing holds, identified by a customer reference supplied with each
  request. Account management is outside this feature.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a test where 200 simultaneous single-unit hold requests target a drop with 50 units,
  exactly 50 succeed and 150 are rejected for insufficient availability. Repeated 100 times, zero runs
  oversell or show negative availability.
- **SC-002**: In 1,000 runs each of confirm-vs-expire and cancel-vs-expire races, every hold ends in exactly
  one final state. After each run, available quantity equals total quantity minus confirmed units, so units
  were returned exactly once.
- **SC-003**: When the same hold request (same request key) is sent 10 times at once, exactly one hold
  exists afterwards and units are consumed only once.
- **SC-004**: 100% of holds that pass their expiry time unconfirmed are reported as EXPIRED and cannot be
  confirmed. Their units are available again within 10 seconds of expiry.
- **SC-005**: With caching and messaging infrastructure made unavailable, all concurrency and lifecycle
  checks in SC-001 to SC-004 still pass.
- **SC-006**: 100% of failure responses carry one of the reason codes listed in FR-029, so a client can
  tell every rejection apart without reading free text.
- **SC-007**: Under 200 simultaneous customers, 95% of hold, confirm, and cancel requests get a definitive
  success or failure answer within 1 second.
- **SC-008**: Drop availability shown to customers matches the true availability within 5 seconds of any
  change.

## Assumptions

- **Drop provisioning**: Drops are provisioned ahead of time by an operator (for example seed data or
  configuration). Customer-facing drop creation, editing, and deletion are out of scope.
- **Customer identity**: Customers are identified by a customer reference sent with each request.
  Authentication and account management are out of scope for this take-home. Hold identifiers are
  hard to guess, and hold operations also require the matching customer reference (FR-022a).
- **Hold duration**: One system-wide configurable hold duration applies to all drops. The default is
  5 minutes.
- **Per-hold maximum**: Each drop has a maximum number of units per hold. The default is 4 if not
  specified.
- **No partial fulfilment**: A hold request is granted in full or rejected. It never grants fewer units than
  requested.
- **No waitlist**: Customers rejected for insufficient availability must retry later. No queue or waitlist
  is provided.
- **Drop close time**: Drops stay open until sold out. Closing times and drop cancellation are out of scope.
- **Out of scope**: Payments, notifications (email or SMS), UIs, refunds or cancellation after confirmation,
  admin dashboards, metrics and tracing, rate limiting, and multi-region deployment.
- **Clock**: The service's clock is the single authority for release and expiry times.
- **Messaging**: If messaging is used at all, it is optional; expiry must not depend on it (FR-027).
