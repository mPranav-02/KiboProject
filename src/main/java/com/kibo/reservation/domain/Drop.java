package com.kibo.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * A limited release of scarce units (data-model.md, table {@code drops}).
 *
 * <p>Inventory rule: {@code availableQuantity} is deliberately not mutable through this entity.
 * It changes only via the atomic conditional UPDATE statements in the repository layer, whose
 * affected-row count decides the outcome (Constitution II, IV). The table's CHECK constraint
 * {@code 0 <= available_quantity <= total_quantity} is defense in depth.
 */
@Entity
@Table(name = "drops")
public class Drop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    /** Fixed for the life of the drop (FR-003). */
    @Column(name = "total_quantity", nullable = false, updatable = false)
    private int totalQuantity;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "max_per_hold", nullable = false)
    private int maxPerHold;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For JPA only. */
    protected Drop() {
    }

    private Drop(String name, String description, int totalQuantity, int maxPerHold, Instant startsAt, Instant now) {
        this.name = name;
        this.description = description;
        this.totalQuantity = totalQuantity;
        this.availableQuantity = totalQuantity;
        this.maxPerHold = maxPerHold;
        this.startsAt = startsAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Creates a new drop with all units available. Used for provisioning/seeding only. */
    public static Drop create(String name, String description, int totalQuantity, int maxPerHold,
                              Instant startsAt, Instant now) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (totalQuantity <= 0) {
            throw new IllegalArgumentException("totalQuantity must be > 0");
        }
        if (maxPerHold < 1) {
            throw new IllegalArgumentException("maxPerHold must be >= 1");
        }
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(now, "now");
        return new Drop(name, description, totalQuantity, maxPerHold, startsAt, now);
    }

    public DropAvailabilityStatus availabilityStatus(Instant now) {
        return DropAvailabilityStatus.of(startsAt, availableQuantity, now);
    }

    public boolean isReleased(Instant now) {
        return !now.isBefore(startsAt);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getMaxPerHold() {
        return maxPerHold;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
