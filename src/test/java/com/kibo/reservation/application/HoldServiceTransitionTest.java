package com.kibo.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kibo.reservation.config.KiboProperties;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.exception.ErrorCode;
import com.kibo.reservation.domain.exception.HoldExpiredException;
import com.kibo.reservation.domain.exception.HoldNotFoundException;
import com.kibo.reservation.domain.exception.InvalidStateTransitionException;
import com.kibo.reservation.domain.exception.InventoryInvariantViolationException;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Service-level tests for confirm (US2) and cancel (US4). Repositories and the transaction manager are
 * mocks (no infrastructure); the guarded UPDATE's affected-row count is simulated, and the transaction
 * manager mock lets each test assert commit vs rollback. The real database behaviour of the guard and the
 * races are proven in {@code it/ConfirmCancelIT} and {@code it/ConfirmCancelRaceIT}.
 */
class HoldServiceTransitionTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    private static final long DROP_ID = 7L;
    private static final String OWNER = "alice";

    private DropRepository drops;
    private HoldRepository holds;
    private PlatformTransactionManager txManager;
    private HoldExpirationService expiration;
    private ApplicationEventPublisher events;
    private HoldService service;
    private Hold hold; // created at NOW, 3 units, expires at NOW + 5 min

    @BeforeEach
    void setUp() {
        drops = mock(DropRepository.class);
        holds = mock(HoldRepository.class);
        txManager = mock(PlatformTransactionManager.class);
        expiration = mock(HoldExpirationService.class);
        events = mock(ApplicationEventPublisher.class);
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        KiboProperties properties = new KiboProperties(
                new KiboProperties.HoldSettings(Duration.ofMinutes(5), 4),
                new KiboProperties.ExpirationSettings(Duration.ofSeconds(2), 200),
                new KiboProperties.CacheSettings(Duration.ofSeconds(3)),
                new KiboProperties.SeedSettings(false));
        service = new HoldService(drops, holds, txManager, Clock.fixed(NOW, ZoneOffset.UTC), properties, expiration, events);
        hold = Hold.createActive(DROP_ID, OWNER, "k1", 3, NOW, Duration.ofMinutes(5));
        when(holds.findById(hold.getId())).thenAnswer(inv -> Optional.of(hold));
    }

    // ---------------------------------------------------------------- confirm

    @Test
    void confirmMovesActiveToConfirmedAndNeverTouchesInventory() {
        when(holds.confirm(hold.getId(), OWNER, NOW)).thenAnswer(inv -> won(HoldStatus.CONFIRMED));

        Hold result = service.confirm(hold.getId(), OWNER);

        assertThat(result.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
        verifyNoInteractions(drops); // confirmation must not return (or take) any inventory
        verify(txManager).commit(any());
        verify(txManager, never()).rollback(any());
    }

    @Test
    void confirmingAnAlreadyConfirmedHoldSucceedsAndChangesNothing() {
        setStatus(HoldStatus.CONFIRMED);
        when(holds.confirm(hold.getId(), OWNER, NOW)).thenReturn(0);

        Hold result = service.confirm(hold.getId(), OWNER);

        assertThat(result.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
        verifyNoInteractions(drops);
        verify(txManager).commit(any());
    }

    @Test
    void confirmingACancelledHoldIsRejectedWithItsCurrentStatus() {
        setStatus(HoldStatus.CANCELLED);
        when(holds.confirm(hold.getId(), OWNER, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(hold.getId(), OWNER))
                .isInstanceOfSatisfying(InvalidStateTransitionException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
                    assertThat(e.currentStatus()).isEqualTo(HoldStatus.CANCELLED);
                });
        verifyNoInteractions(drops);
        verify(txManager).rollback(any());
    }

    @Test
    void confirmingAnExpiredHoldIsRejected() {
        setStatus(HoldStatus.EXPIRED);
        when(holds.confirm(hold.getId(), OWNER, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(hold.getId(), OWNER)).isInstanceOf(HoldExpiredException.class);
        verifyNoInteractions(drops);
    }

    @Test
    void confirmingAnOverdueActiveHoldIsRejectedEvenThoughTheExpiryJobHasNotRun() {
        service = serviceAt(NOW.plus(Duration.ofMinutes(5))); // exactly at expiresAt: no longer confirmable
        when(holds.confirm(hold.getId(), OWNER, NOW.plus(Duration.ofMinutes(5)))).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(hold.getId(), OWNER))
                .isInstanceOfSatisfying(HoldExpiredException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.HOLD_EXPIRED));
        verifyNoInteractions(drops); // this transaction returned nothing...
        verify(expiration).expireOne(hold.getId(), NOW.plus(Duration.ofMinutes(5))); // ...the settle step does, separately
    }

    @Test
    void confirmByAnotherCustomerLooksLikeNotFoundAndChangesNothing() {
        assertThatThrownBy(() -> service.confirm(hold.getId(), "mallory")).isInstanceOf(HoldNotFoundException.class);

        verify(holds, never()).confirm(any(), any(), any());
        verifyNoInteractions(drops);
    }

    @Test
    void confirmOfAnUnknownHoldIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(holds.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(unknown, OWNER))
                .isInstanceOfSatisfying(HoldNotFoundException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.HOLD_NOT_FOUND));
        verify(holds, never()).confirm(any(), any(), any());
    }

    // ----------------------------------------------------------------- cancel

    @Test
    void cancelMovesActiveToCancelledAndReturnsTheUnitsOnceInTheSameTransaction() {
        when(holds.cancel(hold.getId(), OWNER, NOW)).thenAnswer(inv -> won(HoldStatus.CANCELLED));
        when(drops.releaseUnits(DROP_ID, 3, NOW)).thenReturn(1);

        Hold result = service.cancel(hold.getId(), OWNER);

        assertThat(result.getStatus()).isEqualTo(HoldStatus.CANCELLED);
        InOrder order = inOrder(txManager, holds, drops);
        order.verify(txManager).getTransaction(any());
        order.verify(holds).cancel(hold.getId(), OWNER, NOW);
        order.verify(drops).releaseUnits(DROP_ID, 3, NOW); // exactly the hold's quantity, once
        order.verify(txManager).commit(any());
        verify(txManager, never()).rollback(any());
    }

    @Test
    void cancellingAnAlreadyCancelledHoldSucceedsAndReturnsNoUnits() {
        setStatus(HoldStatus.CANCELLED);
        when(holds.cancel(hold.getId(), OWNER, NOW)).thenReturn(0);

        Hold result = service.cancel(hold.getId(), OWNER);

        assertThat(result.getStatus()).isEqualTo(HoldStatus.CANCELLED);
        verify(drops, never()).releaseUnits(anyLong(), anyInt(), any());
        verify(txManager).commit(any());
    }

    @Test
    void cancellingAConfirmedHoldIsRejectedAndReturnsNoUnits() {
        setStatus(HoldStatus.CONFIRMED);
        when(holds.cancel(hold.getId(), OWNER, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(hold.getId(), OWNER))
                .isInstanceOfSatisfying(InvalidStateTransitionException.class,
                        e -> assertThat(e.currentStatus()).isEqualTo(HoldStatus.CONFIRMED));
        verify(drops, never()).releaseUnits(anyLong(), anyInt(), any());
        verify(txManager).rollback(any());
    }

    @Test
    void cancellingAnExpiredOrOverdueHoldIsRejectedAndReturnsNoUnits() {
        setStatus(HoldStatus.EXPIRED);
        when(holds.cancel(hold.getId(), OWNER, NOW)).thenReturn(0);
        assertThatThrownBy(() -> service.cancel(hold.getId(), OWNER)).isInstanceOf(HoldExpiredException.class);

        setStatus(HoldStatus.ACTIVE);
        Instant overdue = NOW.plus(Duration.ofMinutes(6));
        service = serviceAt(overdue);
        when(holds.cancel(hold.getId(), OWNER, overdue)).thenReturn(0);
        assertThatThrownBy(() -> service.cancel(hold.getId(), OWNER)).isInstanceOf(HoldExpiredException.class);

        verify(drops, never()).releaseUnits(anyLong(), anyInt(), any()); // only the settle step may return units
        verify(expiration).expireOne(hold.getId(), overdue);
    }

    @Test
    void anExpiredRejectionStillAnswersHoldExpiredWhenSettlingFails() {
        service = serviceAt(NOW.plus(Duration.ofMinutes(6)));
        when(holds.confirm(any(), any(), any())).thenReturn(0);
        when(expiration.expireOne(any(), any())).thenThrow(new IllegalStateException("database hiccup"));

        assertThatThrownBy(() -> service.confirm(hold.getId(), OWNER)).isInstanceOf(HoldExpiredException.class);
    }

    @Test
    void settlingNeverHappensForRejectionsThatAreNotExpiry() {
        setStatus(HoldStatus.CONFIRMED);
        when(holds.cancel(hold.getId(), OWNER, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(hold.getId(), OWNER)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> service.cancel(hold.getId(), "mallory")).isInstanceOf(HoldNotFoundException.class);

        verifyNoInteractions(expiration);
    }

    @Test
    void cancelByAnotherCustomerLooksLikeNotFoundAndReturnsNoUnits() {
        assertThatThrownBy(() -> service.cancel(hold.getId(), "mallory")).isInstanceOf(HoldNotFoundException.class);

        verify(holds, never()).cancel(any(), any(), any());
        verifyNoInteractions(drops);
    }

    @Test
    void ifTheUnitsCannotBeReturnedTheWholeCancellationRollsBack() {
        when(holds.cancel(hold.getId(), OWNER, NOW)).thenAnswer(inv -> won(HoldStatus.CANCELLED));
        when(drops.releaseUnits(DROP_ID, 3, NOW)).thenReturn(0); // would exceed the drop's total: invariant broken

        assertThatThrownBy(() -> service.cancel(hold.getId(), OWNER))
                .isInstanceOf(InventoryInvariantViolationException.class);

        verify(txManager).rollback(any()); // the CANCELLED status change is undone together with the release
        verify(txManager, never()).commit(any());
    }

    @Test
    void aConfirmRaisesAConfirmedEventButACancelAfterTheUnitsAreReturnedRaisesACancelledEvent() {
        when(holds.confirm(hold.getId(), OWNER, NOW)).thenAnswer(inv -> won(HoldStatus.CONFIRMED));
        service.confirm(hold.getId(), OWNER);

        ArgumentCaptor<Object> confirmed = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(confirmed.capture());
        assertThat(confirmed.getValue()).isInstanceOfSatisfying(HoldLifecycleEvent.class, e -> {
            assertThat(e.type()).isEqualTo(HoldLifecycleEvent.Type.HOLD_CONFIRMED);
            assertThat(e.type().changesAvailability()).isFalse();
            assertThat(e.status()).isEqualTo(HoldStatus.CONFIRMED);
        });

        Hold other = Hold.createActive(DROP_ID, OWNER, "k2", 2, NOW, Duration.ofMinutes(5));
        when(holds.findById(other.getId())).thenReturn(Optional.of(other));
        when(holds.cancel(other.getId(), OWNER, NOW)).thenAnswer(inv -> {
            ReflectionTestUtils.setField(other, "status", HoldStatus.CANCELLED);
            return 1;
        });
        when(drops.releaseUnits(DROP_ID, 2, NOW)).thenReturn(1);
        service.cancel(other.getId(), OWNER);

        ArgumentCaptor<Object> all = ArgumentCaptor.forClass(Object.class);
        InOrder order = inOrder(drops, events);
        order.verify(drops).releaseUnits(DROP_ID, 2, NOW);
        order.verify(events).publishEvent(org.mockito.ArgumentMatchers.<Object>any()); // the cancel event comes after the return
        verify(events, org.mockito.Mockito.times(2)).publishEvent(all.capture());
        assertThat(all.getAllValues().get(1)).isInstanceOfSatisfying(HoldLifecycleEvent.class, e -> {
            assertThat(e.type()).isEqualTo(HoldLifecycleEvent.Type.HOLD_CANCELLED);
            assertThat(e.type().changesAvailability()).isTrue();
            assertThat(e.dropId()).isEqualTo(DROP_ID);
            assertThat(e.quantity()).isEqualTo(2);
        });
    }

    @Test
    void repeatsRejectionsAndAFailedReturnRaiseNoEvent() {
        setStatus(HoldStatus.CANCELLED);
        when(holds.cancel(hold.getId(), OWNER, NOW)).thenReturn(0);
        service.cancel(hold.getId(), OWNER); // repeat

        assertThatThrownBy(() -> service.confirm(hold.getId(), OWNER)) // wrong state
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> service.confirm(hold.getId(), "mallory")).isInstanceOf(HoldNotFoundException.class);

        setStatus(HoldStatus.ACTIVE);
        when(holds.cancel(hold.getId(), OWNER, NOW)).thenAnswer(inv -> won(HoldStatus.CANCELLED));
        when(drops.releaseUnits(DROP_ID, 3, NOW)).thenReturn(0);
        assertThatThrownBy(() -> service.cancel(hold.getId(), OWNER)).isInstanceOf(InventoryInvariantViolationException.class);

        verifyNoInteractions(events);
    }

    // ---------------------------------------------------------------- helpers

    /** Simulates the guarded UPDATE winning: the stored status changes and 1 row is reported. */
    private int won(HoldStatus newStatus) {
        setStatus(newStatus);
        return 1;
    }

    private void setStatus(HoldStatus status) {
        ReflectionTestUtils.setField(hold, "status", status);
    }

    private HoldService serviceAt(Instant now) {
        KiboProperties properties = new KiboProperties(
                new KiboProperties.HoldSettings(Duration.ofMinutes(5), 4),
                new KiboProperties.ExpirationSettings(Duration.ofSeconds(2), 200),
                new KiboProperties.CacheSettings(Duration.ofSeconds(3)),
                new KiboProperties.SeedSettings(false));
        return new HoldService(drops, holds, txManager, Clock.fixed(now, ZoneOffset.UTC), properties, expiration, events);
    }
}
