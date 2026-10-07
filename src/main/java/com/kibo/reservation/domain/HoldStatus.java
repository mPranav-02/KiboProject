package com.kibo.reservation.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Hold lifecycle states and the explicit transition table (Constitution VI, VII; spec FR-011, FR-012).
 *
 * <p>ACTIVE may move only to CONFIRMED, CANCELLED or EXPIRED. Those three are final.
 *
 * <p>This enum is the SINGLE source of the transition rules. Production code never hard-codes them: the
 * services ask {@link #canTransitionTo} / {@link #sourcesOf} and hand the answer to the repository, whose
 * guarded UPDATE ({@code WHERE status IN (:sources)}) then makes concurrent competing transitions produce
 * exactly one winner. The database guard enforces the rule atomically; it never decides what the rule is.
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

    /**
     * The states a hold may be in for a move to {@code target} to be legal, derived from the table above
     * (for every target today that is just ACTIVE). This is what the guarded UPDATE's
     * {@code status IN (...)} condition is built from. Empty when no state can move to {@code target}.
     */
    public static Set<HoldStatus> sourcesOf(HoldStatus target) {
        Set<HoldStatus> sources = EnumSet.noneOf(HoldStatus.class);
        for (HoldStatus candidate : values()) {
            if (candidate.canTransitionTo(target)) {
                sources.add(candidate);
            }
        }
        return Collections.unmodifiableSet(sources);
    }

    /**
     * Whether a hold entering this state gives its units back to the drop (cancelled and expired holds do;
     * a confirmed hold keeps them consumed). Used together with the 1-row guard result so units are
     * returned exactly once.
     */
    public boolean returnsUnitsOnEntry() {
        return this == CANCELLED || this == EXPIRED;
    }

    public boolean isFinal() {
        return allowedTargets().isEmpty();
    }
}
