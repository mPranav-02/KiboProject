package com.kibo.reservation.repository;

import com.kibo.reservation.domain.Drop;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for drops.
 *
 * <p>Inventory is never changed through entity updates. It changes only through the atomic
 * conditional UPDATE statements below, whose affected-row count decides the outcome
 * (Constitution II, IV; research.md §2).
 */
public interface DropRepository extends JpaRepository<Drop, Long> {

    /** All drops in a stable order: soonest release first, then by id. */
    List<Drop> findAllByOrderByStartsAtAscIdAsc();

    /**
     * Atomically takes {@code quantity} units if, and only if, the drop exists, has started and still has
     * enough units. InnoDB holds an exclusive lock on the drop row from this statement until the
     * transaction ends, and evaluates the WHERE clause against the latest committed value, so concurrent
     * callers are serialized and can never take the same unit twice.
     *
     * @return 1 if the units were reserved, 0 if nothing changed (missing, not started, or insufficient)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Drop d
               SET d.availableQuantity = d.availableQuantity - :quantity,
                   d.updatedAt = :now
             WHERE d.id = :dropId
               AND d.availableQuantity >= :quantity
               AND d.startsAt <= :now
            """)
    int reserveUnits(@Param("dropId") long dropId, @Param("quantity") int quantity, @Param("now") Instant now);

    /**
     * Atomically returns {@code quantity} units, if and only if that cannot push availability above the
     * drop's fixed total. Called only from the transaction whose guarded hold transition affected exactly
     * one row, so a hold's units are returned at most once. A result of 0 means an invariant is already
     * broken and the caller must roll back.
     *
     * @return 1 if the units were returned, 0 if nothing changed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Drop d
               SET d.availableQuantity = d.availableQuantity + :quantity,
                   d.updatedAt = :now
             WHERE d.id = :dropId
               AND d.availableQuantity + :quantity <= d.totalQuantity
            """)
    int releaseUnits(@Param("dropId") long dropId, @Param("quantity") int quantity, @Param("now") Instant now);

    /** Current available quantity (latest committed value under READ COMMITTED). Informational only. */
    @Query("SELECT d.availableQuantity FROM Drop d WHERE d.id = :dropId")
    Optional<Integer> findAvailableQuantity(@Param("dropId") long dropId);
}
