package com.kibo.reservation.application;

import com.kibo.reservation.config.KiboProperties;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import com.kibo.reservation.domain.exception.DomainException;
import com.kibo.reservation.domain.exception.DropNotFoundException;
import com.kibo.reservation.domain.exception.DropNotReleasedException;
import com.kibo.reservation.domain.exception.HoldExpiredException;
import com.kibo.reservation.domain.exception.HoldNotFoundException;
import com.kibo.reservation.domain.exception.IdempotencyKeyConflictException;
import com.kibo.reservation.domain.exception.InsufficientInventoryException;
import com.kibo.reservation.domain.exception.InvalidHoldRequestException;
import com.kibo.reservation.domain.exception.InvalidStateTransitionException;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hold use cases: placing a hold (US1), confirming (US2) and cancelling (US4).
 *
 * <p><b>Transaction boundaries are explicit</b> ({@link TransactionTemplate}, not annotations) so they can be
 * read and reviewed in one place:
 * <ol>
 *   <li>{@code placeInTransaction} runs in ONE read-write transaction: idempotency lookup, drop checks,
 *       the atomic conditional decrement, and the INSERT of the ACTIVE hold. Any exception rolls back all of
 *       it, so units are never consumed without a hold and a hold never exists without its units.</li>
 *   <li>Only if that transaction failed on the unique (customer, request key) constraint, a second,
 *       read-only transaction looks up the concurrently committed hold and replays it.</li>
 * </ol>
 *
 * <p>When confirm or cancel finds the hold overdue it answers HOLD_EXPIRED and also settles the hold
 * (see {@link HoldExpirationService}) rather than waiting for the next sweep.
 *
 * <p><b>Confirm and cancel</b> each run in ONE read-write transaction around ONE guarded UPDATE of the hold
 * ({@code WHERE status IN (legal sources) AND expires_at > now}, the sources coming from
 * {@link HoldStatus#sourcesOf}). Its affected-row count is the verdict: 1 means this
 * request won the transition. Cancel then returns the units in the same transaction, and only on that 1-row
 * path, so a hold's units can be returned at most once however many cancels (or confirms) race. If the
 * return fails, the whole transaction, including the status change, rolls back.
 *
 * <p>No JVM-local locking is used anywhere: correctness comes from MySQL (Constitution III, IV, IX).
 */
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final DropRepository drops;
    private final HoldRepository holds;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;
    private final KiboProperties properties;
    private final HoldExpirationService expiration;
    private final ApplicationEventPublisher events;

    public HoldService(DropRepository drops, HoldRepository holds, PlatformTransactionManager transactionManager,
                       Clock clock, KiboProperties properties, HoldExpirationService expiration,
                       ApplicationEventPublisher events) {
        this.drops = drops;
        this.holds = holds;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
        this.properties = properties;
        this.expiration = expiration;
        this.events = events;
    }

    /**
     * Places an all-or-nothing hold, or replays the customer's earlier hold for the same request key.
     *
     * @throws DropNotFoundException           the drop does not exist
     * @throws DropNotReleasedException        the drop has not started yet
     * @throws InvalidHoldRequestException     quantity outside 1..maxPerHold
     * @throws InsufficientInventoryException  not enough units; nothing was changed
     * @throws IdempotencyKeyConflictException the request key was used for a different request
     */
    public HoldPlacement placeHold(PlaceHoldCommand command) {
        try {
            HoldPlacement placement = writeTransaction.execute(status -> placeInTransaction(command, clock.instant()));
            logPlacement(placement);
            return placement;
        } catch (DataIntegrityViolationException duplicateKey) {
            // A concurrent request with the same customer + request key committed first. Our whole
            // transaction, including its inventory decrement, has been rolled back. Replay theirs.
            HoldPlacement replay;
            try {
                replay = readTransaction.execute(status -> holds
                        .findByCustomerIdAndRequestKey(command.customerId(), command.requestKey())
                        .map(existing -> replayOf(existing, command))
                        .orElse(null));
            } catch (DomainException rejected) { // e.g. IDEMPOTENCY_KEY_CONFLICT found while replaying
                logPlacementRejected(command, rejected);
                throw rejected;
            }
            if (replay == null) {
                throw duplicateKey; // some other integrity problem, not a duplicate request key
            }
            logPlacement(replay);
            return replay;
        } catch (DomainException rejected) {
            logPlacementRejected(command, rejected);
            throw rejected;
        }
    }

    /** Every rejected placement is logged with its context and error code (FR-031); the request key is not. */
    private static void logPlacementRejected(PlaceHoldCommand command, DomainException rejected) {
        log.atInfo()
                .addKeyValue("dropId", command.dropId())
                .addKeyValue("customerId", command.customerId())
                .addKeyValue("quantity", command.quantity())
                .addKeyValue("code", rejected.code())
                .log("Hold request rejected");
    }

    /** Everything here commits or rolls back together. Package-private for tests. */
    HoldPlacement placeInTransaction(PlaceHoldCommand command, Instant now) {
        // 1. Idempotency: a retry of an earlier request returns that hold and consumes nothing.
        Optional<Hold> earlier = holds.findByCustomerIdAndRequestKey(command.customerId(), command.requestKey());
        if (earlier.isPresent()) {
            return replayOf(earlier.get(), command);
        }

        // 2-3. The drop must exist and have started (clear, specific errors for clients).
        Drop drop = drops.findById(command.dropId())
                .orElseThrow(() -> new DropNotFoundException(command.dropId()));
        if (!drop.isReleased(now)) {
            throw new DropNotReleasedException(drop.getId(), drop.getStartsAt());
        }

        // 4. Quantity must be within 1..maxPerHold for this drop.
        if (command.quantity() < 1 || command.quantity() > drop.getMaxPerHold()) {
            throw new InvalidHoldRequestException("quantity",
                    "quantity must be between 1 and " + drop.getMaxPerHold() + " for this drop");
        }

        // 5. Atomic conditional decrement. MySQL, not Java, decides whether the units are available.
        int reserved = drops.reserveUnits(command.dropId(), command.quantity(), now);
        if (reserved == 0) {
            // We may have waited on the drop row lock for a concurrent request with our own request key.
            Optional<Hold> concurrent =
                    holds.findByCustomerIdAndRequestKey(command.customerId(), command.requestKey());
            if (concurrent.isPresent()) {
                return replayOf(concurrent.get(), command);
            }
            int available = drops.findAvailableQuantity(command.dropId()).orElse(0);
            throw new InsufficientInventoryException(command.dropId(), command.quantity(), available);
        }

        // 6. Create the ACTIVE hold in the same transaction. Flushed now so that a duplicate request key fails
        //    inside this transaction and rolls back the decrement above.
        Hold hold = Hold.createActive(command.dropId(), command.customerId(), command.requestKey(),
                command.quantity(), now, properties.hold().duration());
        holds.saveAndFlush(hold);
        // Raised inside the transaction; listeners (e.g. cache eviction) only run if it commits.
        events.publishEvent(HoldLifecycleEvent.of(HoldLifecycleEvent.Type.HOLD_CREATED, hold, HoldStatus.ACTIVE, now));
        return new HoldPlacement(hold, true);
    }

    /**
     * Confirms an ACTIVE hold before its expiry; the units stay consumed (no inventory change).
     * Repeating a confirm on a CONFIRMED hold succeeds and changes nothing (FR-021).
     *
     * @throws HoldNotFoundException           unknown hold or not this customer's hold
     * @throws HoldExpiredException            the hold has expired (even if the expiry job has not run yet)
     * @throws InvalidStateTransitionException the hold is CANCELLED
     */
    public Hold confirm(UUID holdId, String customerId) {
        return transition(holdId, customerId, HoldStatus.CONFIRMED);
    }

    /**
     * Cancels an ACTIVE hold before its expiry and returns its units exactly once, in the same transaction.
     * Repeating a cancel on a CANCELLED hold succeeds and changes nothing (FR-021).
     *
     * @throws HoldNotFoundException           unknown hold or not this customer's hold
     * @throws HoldExpiredException            the hold has expired (even if the expiry job has not run yet)
     * @throws InvalidStateTransitionException the hold is CONFIRMED
     */
    public Hold cancel(UUID holdId, String customerId) {
        return transition(holdId, customerId, HoldStatus.CANCELLED);
    }

    /**
     * A customer's own hold, as stored (US5, FR-022). Read-only: it never settles an overdue hold, so the
     * caller shows {@link Hold#effectiveStatus(Instant)} (an ACTIVE hold past its expiry reads as EXPIRED).
     * Another customer's hold, an unknown id and a malformed id are all the same "not found", so the
     * endpoint cannot be used to discover whose hold an id is (FR-022a).
     *
     * @throws HoldNotFoundException unknown hold or not this customer's hold
     */
    public Hold getHold(UUID holdId, String customerId) {
        Optional<Hold> hold = readTransaction.execute(status -> holds.findById(holdId)
                .filter(h -> h.isOwnedBy(customerId)));
        return hold.orElseThrow(() -> {
            log.atInfo()
                    .addKeyValue("holdId", holdId)
                    .addKeyValue("customerId", customerId)
                    .addKeyValue("code", "HOLD_NOT_FOUND")
                    .log("Hold lookup rejected");
            return new HoldNotFoundException(holdId.toString());
        });
    }

    private Hold transition(UUID holdId, String customerId, HoldStatus target) {
        try {
            HoldTransition result = writeTransaction.execute(
                    status -> transitionInTransaction(holdId, customerId, target, clock.instant()));
            log.atInfo()
                    .addKeyValue("holdId", holdId)
                    .addKeyValue("dropId", result.hold().getDropId())
                    .addKeyValue("customerId", customerId)
                    .addKeyValue("quantity", result.hold().getQuantity())
                    .addKeyValue("outcome", result.changed() ? "HOLD_" + target : "HOLD_" + target + "_REPEATED")
                    .log(result.changed() ? "Hold state changed" : "Hold request repeated, nothing changed");
            return result.hold();
        } catch (HoldExpiredException expired) {
            // Already logged with the hold's drop and quantity where it was detected (rejectedTransition).
            settleExpired(holdId);
            throw expired;
        } catch (HoldNotFoundException notFound) {
            // Unknown hold or someone else's: say nothing about the hold itself (no drop, no quantity).
            log.atInfo()
                    .addKeyValue("holdId", holdId)
                    .addKeyValue("customerId", customerId)
                    .addKeyValue("requested", target)
                    .addKeyValue("code", notFound.code())
                    .log("Hold transition rejected");
            throw notFound;
        }
    }

    /**
     * The request was rejected because the hold is overdue. Settle it right away (guarded expire plus return
     * of units, in its own transaction) instead of leaving it for the next sweep. Best effort: the customer
     * gets HOLD_EXPIRED either way, and the sweep will finish the job if this fails.
     */
    private void settleExpired(UUID holdId) {
        try {
            expiration.expireOne(holdId, clock.instant());
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue("holdId", holdId).setCause(e)
                    .log("Could not settle expired hold on contact; the expiry sweep will");
        }
    }

    /**
     * Logs a rejected confirm/cancel for a hold this customer owns, with the hold's drop and quantity
     * (FR-031), and returns the exception to throw.
     */
    private <E extends DomainException> E rejectedTransition(Hold hold, HoldStatus target, E rejection) {
        log.atInfo()
                .addKeyValue("holdId", hold.getId())
                .addKeyValue("dropId", hold.getDropId())
                .addKeyValue("customerId", hold.getCustomerId())
                .addKeyValue("quantity", hold.getQuantity())
                .addKeyValue("requested", target)
                .addKeyValue("currentStatus", hold.getStatus())
                .addKeyValue("code", rejection.code())
                .log("Hold transition rejected");
        return rejection;
    }

    /**
     * The whole confirm/cancel transaction. Package-private for tests.
     *
     * <ol>
     *   <li>Load the hold; a missing hold or another customer's hold is "not found". Quantity and drop
     *       never change, so this plain read cannot go stale where it matters.</li>
     *   <li>One guarded UPDATE decides the winner. Competing requests queue on the hold row and re-evaluate
     *       the WHERE clause against the committed result, so exactly one of them sees 1 row.</li>
     *   <li>Winner only: cancel returns the units (an UPDATE of the drop row in the same transaction).
     *       Confirm touches no inventory.</li>
     *   <li>Losers (0 rows) re-read the hold to explain why: idempotent success, expired, or invalid.</li>
     * </ol>
     */
    HoldTransition transitionInTransaction(UUID holdId, String customerId, HoldStatus target, Instant now) {
        Hold hold = holds.findById(holdId)
                .filter(h -> h.isOwnedBy(customerId))
                .orElseThrow(() -> new HoldNotFoundException(holdId.toString()));

        // The transition table is HoldStatus: the repository only enforces it atomically, it never decides it.
        Set<HoldStatus> sources = HoldStatus.sourcesOf(target);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("No hold state can move to " + target);
        }
        int changed = holds.moveBeforeExpiry(holdId, customerId, target, sources, now);

        if (changed == 1) {
            if (target.returnsUnitsOnEntry()) {
                UnitRelease.returnUnits(drops, hold, now); // throws (rolling everything back) if it cannot
            }
            Hold updated = reload(holdId);
            events.publishEvent(HoldLifecycleEvent.of(lifecycleEventFor(target), updated, target, now));
            return new HoldTransition(updated, true);
        }

        // Nothing changed: someone else got there first, or the request was never valid. Re-read the
        // latest committed state (the guarded update cleared the persistence context) and explain.
        Hold current = reload(holdId);
        if (current.getStatus() == target) {
            return new HoldTransition(current, false); // repeated confirm/cancel: idempotent success
        }
        if (current.effectiveStatus(now) == HoldStatus.EXPIRED) {
            throw rejectedTransition(current, target, new HoldExpiredException(holdId, current.getExpiresAt()));
        }
        if (current.getStatus().canTransitionTo(target)) {
            // The table allows it and the hold is not overdue, yet the guarded update changed nothing:
            // impossible unless the guard or the clock is broken. Fail loudly instead of guessing.
            throw new IllegalStateException("Guarded transition to " + target + " changed nothing for "
                    + current.getStatus() + " hold " + holdId);
        }
        throw rejectedTransition(current, target,
                new InvalidStateTransitionException(holdId, current.getStatus(), target));
    }

    private static HoldLifecycleEvent.Type lifecycleEventFor(HoldStatus target) {
        return switch (target) {
            case CONFIRMED -> HoldLifecycleEvent.Type.HOLD_CONFIRMED;
            case CANCELLED -> HoldLifecycleEvent.Type.HOLD_CANCELLED;
            case EXPIRED -> HoldLifecycleEvent.Type.HOLD_EXPIRED;
            case ACTIVE -> HoldLifecycleEvent.Type.HOLD_CREATED;
        };
    }

    private Hold reload(UUID holdId) {
        return holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId.toString()));
    }

    /** Result of a confirm/cancel: the hold now, and whether this call changed it. */
    record HoldTransition(Hold hold, boolean changed) {
    }

    /**
     * A replay returns the earlier hold only when it is THIS customer's hold for the same request. The
     * lookup is already by (customer, key) on a case-sensitive column; the owner check is defence in depth, so
     * that a lookup that ever matched someone else's hold (a collation mistake, say) can never hand out
     * that hold's id or data: it is refused as a key conflict, which reveals nothing about the other hold.
     */
    private static HoldPlacement replayOf(Hold existing, PlaceHoldCommand command) {
        if (!existing.isOwnedBy(command.customerId())
                || !existing.matchesRequest(command.dropId(), command.quantity())) {
            throw new IdempotencyKeyConflictException(command.requestKey());
        }
        return new HoldPlacement(existing, false);
    }

    private static void logPlacement(HoldPlacement placement) {
        Hold hold = placement.hold();
        log.atInfo()
                .addKeyValue("holdId", hold.getId())
                .addKeyValue("dropId", hold.getDropId())
                .addKeyValue("customerId", hold.getCustomerId())
                .addKeyValue("quantity", hold.getQuantity())
                .addKeyValue("outcome", placement.created() ? "HOLD_CREATED" : "HOLD_REPLAYED")
                .log(placement.created() ? "Hold placed" : "Hold request replayed");
    }
}
