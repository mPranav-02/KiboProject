package com.kibo.reservation.repository;

import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for holds. Hold status is never changed through entity updates. Every transition is one
 * guarded UPDATE whose WHERE clause requires the hold to still be in a legal source state: InnoDB serializes
 * competing updates of the same row and re-evaluates the clause against the latest committed state, so when
 * several requests compete for one hold exactly one of them affects a row (FR-025). The legal source states
 * are passed in from {@code HoldStatus}, the single source of the transition rules; no query hard-codes one.
 */
public interface HoldRepository extends JpaRepository<Hold, UUID> {

    /** Idempotency lookup: at most one hold per (customer, request key), enforced by a unique key. */
    Optional<Hold> findByCustomerIdAndRequestKey(String customerId, String requestKey);

    /**
     * Moves a hold to {@code target} for a customer-driven transition (confirm or cancel): only for the owning
     * customer, only while the hold is in one of {@code sources} and only strictly before expiry (FR-014 to
     * FR-017, FR-022a).
     *
     * <p>The caller passes {@code sources} from {@code HoldStatus.sourcesOf(target)}: the transition rules live
     * in {@code HoldStatus} alone and this query only enforces them atomically (Constitution VI). The caller
     * returns the units in the same transaction, and only when this returns 1.
     *
     * @return 1 if this call made the transition, 0 if it changed nothing (the caller then classifies why)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Hold h
               SET h.status = :target,
                   h.resolvedAt = :now,
                   h.updatedAt = :now
             WHERE h.id = :id
               AND h.customerId = :customerId
               AND h.status IN :sources
               AND h.expiresAt > :now
            """)
    int moveBeforeExpiry(@Param("id") UUID id, @Param("customerId") String customerId,
                         @Param("target") HoldStatus target, @Param("sources") Collection<HoldStatus> sources,
                         @Param("now") Instant now);

    /**
     * Moves a hold to {@code target} for the system-driven transition (expire): only while the hold is in one
     * of {@code sources} and only once it is at or past its expiry time (FR-018). The caller returns the units
     * in the same transaction, and only when this returns 1. Competing sweepers, cancels and confirms race on
     * this same status guard, so exactly one of them changes the row.
     *
     * @return 1 if this call made the transition, 0 if it changed nothing (already resolved, or not yet due)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Hold h
               SET h.status = :target,
                   h.resolvedAt = :now,
                   h.updatedAt = :now
             WHERE h.id = :id
               AND h.status IN :sources
               AND h.expiresAt <= :now
            """)
    int moveAtOrAfterExpiry(@Param("id") UUID id, @Param("target") HoldStatus target,
                            @Param("sources") Collection<HoldStatus> sources, @Param("now") Instant now);

    /**
     * Ids of holds that are at or past their expiry time and still in one of {@code sources} (the states that
     * may move to EXPIRED, from {@code HoldStatus.sourcesOf(EXPIRED)}), oldest first (served by index
     * {@code (status, expires_at)}). A plain read with no locks: it is only a list of candidates, and each one
     * is re-checked by the guarded {@link #moveAtOrAfterExpiry} update, so a stale or overlapping list is
     * harmless.
     */
    @Query("""
            SELECT h.id FROM Hold h
             WHERE h.status IN :sources
               AND h.expiresAt <= :now
             ORDER BY h.expiresAt ASC, h.id ASC
            """)
    List<UUID> findOverdueIds(@Param("sources") Collection<HoldStatus> sources, @Param("now") Instant now,
                              Pageable page);
}
