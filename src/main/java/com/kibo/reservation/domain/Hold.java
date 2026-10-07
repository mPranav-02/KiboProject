package com.kibo.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * A customer's time-limited claim on units of one drop (data-model.md, table {@code holds}).
 *
 * <p>State rule: {@code status} has no setter. Every transition (confirm/cancel/expire) is a guarded
 * atomic UPDATE ({@code WHERE status = 'ACTIVE' ...}) in the repository layer, validated against
 * {@link HoldStatus}'s transition table, so concurrent competitors produce exactly one winner.
 *
 * <p>Implements {@link Persistable} because the UUID id is assigned by the application: without it,
 * Spring Data would treat a new hold as existing and issue a merge (extra SELECT) instead of an INSERT.
 */
@Entity
@Table(name = "holds")
public class Hold implements Persistable<UUID> {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    /** Plain foreign key (no JPA association): holds are always handled by id, never navigated. */
    @Column(name = "drop_id", nullable = false, updatable = false)
    private Long dropId;

    @Column(name = "customer_id", nullable = false, updatable = false, length = 64)
    private String customerId;

    @Column(name = "request_key", nullable = false, updatable = false, length = 64)
    private String requestKey;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    /** Stored as VARCHAR(16) (+ CHECK constraint), not a MySQL ENUM. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    /** For JPA only. */
    protected Hold() {
    }

    private Hold(UUID id, Long dropId, String customerId, String requestKey, int quantity,
                 Instant now, Duration holdDuration) {
        this.id = id;
        this.dropId = dropId;
        this.customerId = customerId;
        this.requestKey = requestKey;
        this.quantity = quantity;
        this.status = HoldStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = now.plus(holdDuration);
    }

    /**
     * Creates a new ACTIVE hold expiring at {@code now + holdDuration} (FR-008). The per-drop maximum
     * is checked by the application layer, which knows the drop.
     */
    public static Hold createActive(Long dropId, String customerId, String requestKey, int quantity,
                                    Instant now, Duration holdDuration) {
        Objects.requireNonNull(dropId, "dropId");
        requireText(customerId, "customerId");
        requireText(requestKey, "requestKey");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(holdDuration, "holdDuration");
        if (holdDuration.isZero() || holdDuration.isNegative()) {
            throw new IllegalArgumentException("holdDuration must be positive");
        }
        return new Hold(UUID.randomUUID(), dropId, customerId, requestKey, quantity, now, holdDuration);
    }

    /**
     * Status as customers must see it (FR-019): an ACTIVE hold at or past its expiry time is reported
     * as EXPIRED even if the expiry job has not processed it yet. Read-only; does not change state.
     */
    public HoldStatus effectiveStatus(Instant now) {
        return status == HoldStatus.ACTIVE && isOverdue(now) ? HoldStatus.EXPIRED : status;
    }

    /** True at or after {@code expiresAt}: confirm/cancel require {@code now < expiresAt}. */
    public boolean isOverdue(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isOwnedBy(String customer) {
        return customerId.equals(customer);
    }

    /** Whether a replayed request (same customer + request key) carries the same details (FR-009). */
    public boolean matchesRequest(Long requestedDropId, int requestedQuantity) {
        return dropId.equals(requestedDropId) && quantity == requestedQuantity;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public Long getDropId() {
        return dropId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public int getQuantity() {
        return quantity;
    }

    /** Stored status. Use {@link #effectiveStatus(Instant)} for anything shown to customers. */
    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
