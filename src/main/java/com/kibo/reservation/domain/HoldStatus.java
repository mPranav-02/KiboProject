package com.kibo.reservation.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Hold lifecycle states and the explicit transition table (Constitution VI, VII; spec FR-011, FR-012).
 *
 * <p>ACTIVE may move only to CONFIRMED, CANCELLED or EXPIRED. Those three are final. The persistence
 * layer additionally guards every transition with {@code WHERE status = 'ACTIVE'} so that concurrent
 * competing transitions produce exactly one winner; this enum is the single source of the rules.
 */
public enum HoldStatus {
    ACTIVE,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    /** States this state may transition to. Empty for final states. */
    public Set<HoldStatus> allowedTargets() {
        return switch (this) {
            case ACTIVE -> Collections.unmodifiableSet(EnumSet.of(CONFIRMED, CANCELLED, EXPIRED));
            case CONFIRMED, CANCELLED, EXPIRED -> Collections.emptySet();
        };
    }

    public boolean canTransitionTo(HoldStatus target) {
        return target != null && allowedTargets().contains(target);
    }

    public boolean isFinal() {
        return allowedTargets().isEmpty();
    }
}
