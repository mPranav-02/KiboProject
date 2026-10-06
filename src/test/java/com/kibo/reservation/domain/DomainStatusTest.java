package com.kibo.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Boundaries of derived statuses and entity factories. Pure unit tests, no Spring, no infrastructure. */
class DomainStatusTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00.000000Z");

    @Nested
    class DropAvailability {

        @Test
        void upcomingBeforeRelease() {
            Instant startsAt = NOW.plusNanos(1_000);
            assertThat(DropAvailabilityStatus.of(startsAt, 10, NOW)).isEqualTo(DropAvailabilityStatus.UPCOMING);
            // Even with no units, a drop that has not started is UPCOMING, not SOLD_OUT.
            assertThat(DropAvailabilityStatus.of(startsAt, 0, NOW)).isEqualTo(DropAvailabilityStatus.UPCOMING);
        }

        @Test
        void openExactlyAtReleaseTime() {
            assertThat(DropAvailabilityStatus.of(NOW, 1, NOW)).isEqualTo(DropAvailabilityStatus.OPEN);
        }

        @Test
        void soldOutWhenReleasedWithNoUnits() {
            assertThat(DropAvailabilityStatus.of(NOW.minusSeconds(60), 0, NOW))
                    .isEqualTo(DropAvailabilityStatus.SOLD_OUT);
        }
    }

    @Nested
    class DropFactory {

        @Test
        void newDropHasAllUnitsAvailable() {
            Drop drop = Drop.create("Sneaker", "Limited", 50, 4, NOW.plusSeconds(600), NOW);
            assertThat(drop.getTotalQuantity()).isEqualTo(50);
            assertThat(drop.getAvailableQuantity()).isEqualTo(50);
            assertThat(drop.getMaxPerHold()).isEqualTo(4);
            assertThat(drop.getCreatedAt()).isEqualTo(NOW);
            assertThat(drop.getUpdatedAt()).isEqualTo(NOW);
            assertThat(drop.availabilityStatus(NOW)).isEqualTo(DropAvailabilityStatus.UPCOMING);
            assertThat(drop.isReleased(NOW)).isFalse();
            assertThat(drop.isReleased(NOW.plusSeconds(600))).isTrue();
        }

        @Test
        void rejectsInvalidDefinitions() {
            assertThatThrownBy(() -> Drop.create(" ", null, 1, 1, NOW, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Drop.create("x", null, 0, 1, NOW, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Drop.create("x", null, 5, 0, NOW, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class HoldFactoryAndEffectiveStatus {

        private final Hold hold = Hold.createActive(1L, "alice", "k1", 2, NOW, Duration.ofMinutes(5));

        @Test
        void newHoldIsActiveAndExpiresAfterTheHoldDuration() {
            assertThat(hold.getId()).isNotNull();
            assertThat(hold.isNew()).isTrue();
            assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
            assertThat(hold.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
            assertThat(hold.getResolvedAt()).isNull();
            assertThat(hold.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        void activeUntilOneMicrosecondBeforeExpiry() { // DATETIME(6) resolution
            Instant justBefore = hold.getExpiresAt().minusNanos(1_000);
            assertThat(hold.effectiveStatus(justBefore)).isEqualTo(HoldStatus.ACTIVE);
            assertThat(hold.isOverdue(justBefore)).isFalse();
        }

        @Test
        void reportedExpiredExactlyAtExpiryTime() {
            // Spec edge case: confirm/cancel at or after expiresAt is rejected; reads show EXPIRED (FR-015, FR-019).
            assertThat(hold.effectiveStatus(hold.getExpiresAt())).isEqualTo(HoldStatus.EXPIRED);
            assertThat(hold.isOverdue(hold.getExpiresAt())).isTrue();
            // Reading does not change the stored state.
            assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        }

        @Test
        void ownershipAndReplayMatching() {
            assertThat(hold.isOwnedBy("alice")).isTrue();
            assertThat(hold.isOwnedBy("bob")).isFalse();
            assertThat(hold.matchesRequest(1L, 2)).isTrue();
            assertThat(hold.matchesRequest(1L, 3)).isFalse();
            assertThat(hold.matchesRequest(2L, 2)).isFalse();
        }

        @Test
        void rejectsInvalidHolds() {
            Duration d = Duration.ofMinutes(5);
            assertThatThrownBy(() -> Hold.createActive(1L, "alice", "k", 0, NOW, d))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Hold.createActive(1L, " ", "k", 1, NOW, d))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Hold.createActive(1L, "alice", "", 1, NOW, d))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Hold.createActive(1L, "alice", "k", 1, NOW, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
