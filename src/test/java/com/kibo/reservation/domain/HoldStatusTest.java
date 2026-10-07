package com.kibo.reservation.domain;

import static com.kibo.reservation.domain.HoldStatus.ACTIVE;
import static com.kibo.reservation.domain.HoldStatus.CANCELLED;
import static com.kibo.reservation.domain.HoldStatus.CONFIRMED;
import static com.kibo.reservation.domain.HoldStatus.EXPIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every (from, to) pair of the hold transition table (FR-011, FR-012; Constitution VI, VII). */
class HoldStatusTest {

    private static final Set<HoldStatus> FROM_ACTIVE = EnumSet.of(CONFIRMED, CANCELLED, EXPIRED);

    static Stream<Arguments> allPairs() {
        return Stream.of(HoldStatus.values())
                .flatMap(from -> Stream.of(HoldStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void onlyActiveMayTransitionAndOnlyToTheThreeFinalStates(HoldStatus from, HoldStatus to) {
        boolean expected = from == ACTIVE && FROM_ACTIVE.contains(to);
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }

    @Test
    void activeIsTheOnlyNonFinalState() {
        assertThat(ACTIVE.isFinal()).isFalse();
        assertThat(CONFIRMED.isFinal()).isTrue();
        assertThat(CANCELLED.isFinal()).isTrue();
        assertThat(EXPIRED.isFinal()).isTrue();
    }

    @Test
    void selfTransitionsAndNullAreNeverAllowed() {
        for (HoldStatus status : HoldStatus.values()) {
            assertThat(status.canTransitionTo(status)).as("%s -> itself", status).isFalse();
            assertThat(status.canTransitionTo(null)).isFalse();
        }
    }

    @Test
    void allowedTargetsCannotBeModified() {
        assertThat(ACTIVE.allowedTargets()).containsExactlyInAnyOrder(CONFIRMED, CANCELLED, EXPIRED);
        assertThatThrownBy(() -> ACTIVE.allowedTargets().add(ACTIVE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sourcesAreDerivedFromTheTransitionTable() {
        for (HoldStatus target : new HoldStatus[] {CONFIRMED, CANCELLED, EXPIRED}) {
            assertThat(HoldStatus.sourcesOf(target)).as("sources of %s", target).containsExactly(ACTIVE);
        }
        assertThat(HoldStatus.sourcesOf(ACTIVE)).as("nothing moves back to ACTIVE").isEmpty();
        assertThat(HoldStatus.sourcesOf(null)).isEmpty();
        assertThatThrownBy(() -> HoldStatus.sourcesOf(CONFIRMED).add(CONFIRMED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void onlyCancelledAndExpiredReturnUnits() {
        assertThat(CANCELLED.returnsUnitsOnEntry()).isTrue();
        assertThat(EXPIRED.returnsUnitsOnEntry()).isTrue();
        assertThat(CONFIRMED.returnsUnitsOnEntry()).isFalse();
        assertThat(ACTIVE.returnsUnitsOnEntry()).isFalse();
    }
}
