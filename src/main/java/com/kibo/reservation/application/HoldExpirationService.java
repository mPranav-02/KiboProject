package com.kibo.reservation.application;

import com.kibo.reservation.config.KiboProperties;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expires overdue ACTIVE holds and returns their units (US3, FR-018, FR-020, FR-025).
 *
 * <p><b>Why this is safe against everything that can run at the same time</b> (other sweepers on this or
 * other instances, a customer's cancel or confirm, a repeated sweep):
 * <ul>
 *   <li>Finding candidates is a plain, lock-free read. It is only a list of ids to try.</li>
 *   <li>Each hold is expired in its OWN transaction by ONE guarded UPDATE
 *       ({@code WHERE status = 'ACTIVE' AND expires_at <= now}). InnoDB serializes competing updates of the
 *       row and re-evaluates the guard against the latest committed state, so when an expire, a cancel and a
 *       confirm compete, exactly one affects a row.</li>
 *   <li>Units are returned in that same transaction and only when the update affected exactly one row, so
 *       they come back exactly once; if the return fails the whole transaction, including the status change,
 *       rolls back.</li>
 *   <li>One failing hold has its own transaction, so it cannot undo or block the others.</li>
 * </ul>
 * No JVM-level locking, leader election or "only one instance runs this" assumption is used (Constitution IX).
 */
@Service
public class HoldExpirationService {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirationService.class);

    /** Outcome of trying to expire one hold. */
    public enum Outcome {
        /** This call moved the hold ACTIVE to EXPIRED and returned its units. */
        EXPIRED,
        /** Nothing changed: already resolved by someone else, not due yet, or no such hold. */
        SKIPPED
    }

    /** Result of one sweep: candidates seen, holds this sweep expired, holds that failed (retried next sweep). */
    public record Summary(int found, int expired, int failed) {
    }

    private final DropRepository drops;
    private final HoldRepository holds;
    private final TransactionTemplate writeTransaction;
    private final int batchSize;
    private final ApplicationEventPublisher events;

    public HoldExpirationService(DropRepository drops, HoldRepository holds,
                                 PlatformTransactionManager transactionManager, KiboProperties properties,
                                 ApplicationEventPublisher events) {
        this.drops = drops;
        this.holds = holds;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.batchSize = properties.expiration().batchSize();
        this.events = events;
    }

    /**
     * Expires every hold that is overdue at {@code now}, in batches, until none are left. A failure on one
     * hold is logged and the sweep carries on; that hold is simply retried by the next sweep.
     */
    public Summary expireOverdue(Instant now) {
        int found = 0;
        int expired = 0;
        int failed = 0;
        while (true) {
            List<UUID> page = holds.findOverdueActiveIds(now, PageRequest.of(0, batchSize));
            found += page.size();
            int expiredInPage = 0;
            for (UUID id : page) {
                try {
                    if (expireOne(id, now) == Outcome.EXPIRED) {
                        expiredInPage++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.atWarn()
                            .addKeyValue("holdId", id)
                            .addKeyValue("outcome", "EXPIRE_FAILED")
                            .setCause(e)
                            .log("Could not expire hold; it will be retried by the next sweep");
                }
            }
            expired += expiredInPage;
            // Continue only while pages are full AND this sweep is making progress, so holds that keep
            // failing (or are being expired by another sweeper) can never trap this loop.
            if (page.size() < batchSize || expiredInPage == 0) {
                return new Summary(found, expired, failed);
            }
        }
    }

    /**
     * Expires one hold in its own transaction: guarded UPDATE, then, only if it affected one row, return
     * the units. Idempotent and race-safe: a second call, or a call that lost to a cancel or confirm,
     * returns {@link Outcome#SKIPPED} and changes nothing.
     */
    public Outcome expireOne(UUID holdId, Instant now) {
        return writeTransaction.execute(status -> expireInTransaction(holdId, now));
    }

    /** Everything here commits or rolls back together. Package-private for tests. */
    Outcome expireInTransaction(UUID holdId, Instant now) {
        // Quantity and drop never change, so this plain read cannot go stale where it matters.
        Optional<Hold> hold = holds.findById(holdId);
        if (hold.isEmpty()) {
            return Outcome.SKIPPED;
        }
        if (holds.expire(holdId, now) != 1) {
            return Outcome.SKIPPED; // someone else resolved it first, or it is not due yet
        }
        UnitRelease.returnUnits(drops, hold.get(), now); // throws, rolling the status change back, if it cannot
        events.publishEvent(HoldLifecycleEvent.of(
                HoldLifecycleEvent.Type.HOLD_EXPIRED, hold.get(), HoldStatus.EXPIRED, now));
        log.atInfo()
                .addKeyValue("holdId", holdId)
                .addKeyValue("dropId", hold.get().getDropId())
                .addKeyValue("customerId", hold.get().getCustomerId())
                .addKeyValue("quantity", hold.get().getQuantity())
                .addKeyValue("outcome", "HOLD_EXPIRED")
                .log("Hold expired, units returned");
        return Outcome.EXPIRED;
    }
}
