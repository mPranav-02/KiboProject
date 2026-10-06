package com.kibo.reservation.repository;

import com.kibo.reservation.domain.Hold;
import java.time.Instant;
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
 * guarded UPDATE whose WHERE clause requires {@code status = 'ACTIVE'}: InnoDB serializes competing
 * updates of the same row and re-evaluates the clause against the latest committed state, so when
 * several requests compete for one hold exactly one of them affects a row (FR-025).
 */
public interface HoldRepository extends JpaRepository<Hold, UUID> {

    /** Idempotency lookup: at most one hold per (customer, request key), enforced by a unique key. */
    Optional<Hold> findByCustomerIdAndRequestKey(String customerId, String requestKey);

    /**
     * ACTIVE to CONFIRMED, only for the owning customer and only strictly before expiry (FR-014, FR-015,
     * FR-022a).
     *
     * @return 1 if this call confirmed the hold, 0 if it changed nothing (the caller then classifies why)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Hold h
               SET h.status = com.kibo.reservation.domain.HoldStatus.CONFIRMED,
                   h.resolvedAt = :now,
                   h.updatedAt = :now
             WHERE h.id = :id
               AND h.customerId = :customerId
               AND h.status = com.kibo.reservation.domain.HoldStatus.ACTIVE
               AND h.expiresAt > :now
            """)
    int confirm(@Param("id") UUID id, @Param("customerId") String customerId, @Param("now") Instant now);

    /**
     * ACTIVE to CANCELLED, only for the owning customer and only strictly before expiry (FR-016, FR-017,
     * FR-022a). The caller returns the units in the same transaction, and only when this returns 1.
     *
     * @return 1 if this call cancelled the hold, 0 if it changed nothing
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Hold h
               SET h.status = com.kibo.reservation.domain.HoldStatus.CANCELLED,
                   h.resolvedAt = :now,
                   h.updatedAt = :now
             WHERE h.id = :id
               AND h.customerId = :customerId
               AND h.status = com.kibo.reservation.domain.HoldStatus.ACTIVE
               AND h.expiresAt > :now
            """)
    int cancel(@Param("id") UUID id, @Param("customerId") String customerId, @Param("now") Instant now);

    /**
     * ACTIVE to EXPIRED, only once the hold is at or past its expiry time (FR-018). The caller returns the
     * units in the same transaction, and only when this returns 1. Competing sweepers, cancels and confirms
     * race on this same guard, so exactly one of them changes the row.
     *
     * @return 1 if this call expired the hold, 0 if it changed nothing (already resolved, or not yet due)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Hold h
               SET h.status = com.kibo.reservation.domain.HoldStatus.EXPIRED,
                   h.resolvedAt = :now,
                   h.updatedAt = :now
             WHERE h.id = :id
               AND h.status = com.kibo.reservation.domain.HoldStatus.ACTIVE
               AND h.expiresAt <= :now
            """)
    int expire(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Ids of ACTIVE holds at or past their expiry time, oldest first (served by index
     * {@code (status, expires_at)}). A plain read with no locks: it is only a list of candidates, and each
     * one is re-checked by the guarded {@link #expire} update, so a stale or overlapping list is harmless.
     */
    @Query("""
            SELECT h.id FROM Hold h
             WHERE h.status = com.kibo.reservation.domain.HoldStatus.ACTIVE
               AND h.expiresAt <= :now
             ORDER BY h.expiresAt ASC, h.id ASC
            """)
    List<UUID> findOverdueActiveIds(@Param("now") Instant now, Pageable page);
}
