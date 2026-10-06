package com.kibo.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kibo.reservation.application.HoldExpirationService.Outcome;
import com.kibo.reservation.application.HoldExpirationService.Summary;
import com.kibo.reservation.config.KiboProperties;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.exception.InventoryInvariantViolationException;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Unit tests for expiry (US3). Repositories and the transaction manager are mocks (no infrastructure); the
 * guarded UPDATE's affected-row count is simulated. The real database behaviour of the guard, the
 * concurrent sweepers and the races are proven in {@code ExpirationIT} and {@code HoldRaceIT}.
 */
class HoldExpirationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:10:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-06T10:00:00Z");
    private static final long DROP_ID = 7L;
    private static final int BATCH = 2;

    private DropRepository drops;
    private HoldRepository holds;
    private PlatformTransactionManager txManager;
    private ApplicationEventPublisher events;
    private HoldExpirationService service;

    @BeforeEach
    void setUp() {
        drops = mock(DropRepository.class);
        holds = mock(HoldRepository.class);
        txManager = mock(PlatformTransactionManager.class);
        events = mock(ApplicationEventPublisher.class);
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        KiboProperties properties = new KiboProperties(
                new KiboProperties.HoldSettings(Duration.ofMinutes(5), 4),
                new KiboProperties.ExpirationSettings(Duration.ofSeconds(2), BATCH),
                new KiboProperties.CacheSettings(Duration.ofSeconds(3)),
                new KiboProperties.SeedSettings(false));
        service = new HoldExpirationService(drops, holds, txManager, properties, events);
    }

    // ------------------------------------------------------------ expire one

    @Test
    void expiringAnOverdueHoldChangesItsStatusThenReturnsExactlyItsUnitsInOneCommittedTransaction() {
        Hold hold = overdueHold(3);
        when(holds.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(holds.expire(hold.getId(), NOW)).thenReturn(1);
        when(drops.releaseUnits(DROP_ID, 3, NOW)).thenReturn(1);

        assertThat(service.expireOne(hold.getId(), NOW)).isEqualTo(Outcome.EXPIRED);

        InOrder order = inOrder(txManager, holds, drops);
        order.verify(txManager).getTransaction(any());
        order.verify(holds).expire(hold.getId(), NOW);
        order.verify(drops).releaseUnits(DROP_ID, 3, NOW);
        order.verify(txManager).commit(any());
        verify(txManager, never()).rollback(any());
    }

    @Test
    void ifTheGuardedUpdateChangesNothingNoUnitsAreReturned() {
        // already confirmed/cancelled/expired by someone else, or not due yet
        Hold hold = overdueHold(3);
        when(holds.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(holds.expire(hold.getId(), NOW)).thenReturn(0);

        assertThat(service.expireOne(hold.getId(), NOW)).isEqualTo(Outcome.SKIPPED);

        verify(drops, never()).releaseUnits(anyLong(), anyInt(), any());
        verify(txManager).commit(any());
    }

    @Test
    void anUnknownHoldIsSkipped() {
        UUID unknown = UUID.randomUUID();
        when(holds.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.expireOne(unknown, NOW)).isEqualTo(Outcome.SKIPPED);

        verify(holds, never()).expire(any(), any());
        verify(drops, never()).releaseUnits(anyLong(), anyInt(), any());
    }

    @Test
    void ifTheUnitsCannotBeReturnedTheExpiryRollsBackWithThem() {
        Hold hold = overdueHold(3);
        when(holds.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(holds.expire(hold.getId(), NOW)).thenReturn(1);
        when(drops.releaseUnits(DROP_ID, 3, NOW)).thenReturn(0); // would exceed the drop's total

        assertThatThrownBy(() -> service.expireOne(hold.getId(), NOW))
                .isInstanceOf(InventoryInvariantViolationException.class);

        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
    }

    // ------------------------------------------------------------ the sweep

    @Test
    void sweepsInBatchesUntilAShortPageIsReturned() {
        Hold a = overdueHold(1), b = overdueHold(1), c = overdueHold(1);
        givenPages(List.of(a.getId(), b.getId()), List.of(c.getId()));
        for (Hold h : List.of(a, b, c)) {
            givenExpires(h);
        }

        Summary summary = service.expireOverdue(NOW);

        assertThat(summary).isEqualTo(new Summary(3, 3, 0));
        verify(holds, times(2)).findOverdueActiveIds(eq(NOW), any(Pageable.class));
        verify(drops, times(3)).releaseUnits(eq(DROP_ID), eq(1), eq(NOW));
    }

    @Test
    void anEmptySweepDoesNothing() {
        givenPages(List.of());

        assertThat(service.expireOverdue(NOW)).isEqualTo(new Summary(0, 0, 0));

        verify(holds, never()).expire(any(), any());
        verify(drops, never()).releaseUnits(anyLong(), anyInt(), any());
    }

    @Test
    void zeroRowExpiriesAreNoOpsAndAFullPageWithNoProgressEndsTheSweep() {
        // two candidates that another sweeper already expired: every guarded update affects 0 rows
        Hold a = overdueHold(1), b = overdueHold(1);
        when(holds.findById(any())).thenAnswer(inv -> Optional.of(inv.getArgument(0).equals(a.getId()) ? a : b));
        when(holds.findOverdueActiveIds(eq(NOW), any(Pageable.class))).thenReturn(List.of(a.getId(), b.getId()));
        when(holds.expire(any(), eq(NOW))).thenReturn(0);

        Summary summary = service.expireOverdue(NOW);

        assertThat(summary).isEqualTo(new Summary(2, 0, 0));
        verify(holds, times(1)).findOverdueActiveIds(eq(NOW), any(Pageable.class)); // did not loop forever
        verify(drops, never()).releaseUnits(anyLong(), anyInt(), any());
    }

    @Test
    void aFailureOnOneHoldIsLoggedAndTheBatchContinues() {
        Hold bad = overdueHold(1), good = overdueHold(1);
        givenPages(List.of(bad.getId(), good.getId()), List.of());
        when(holds.findById(bad.getId())).thenReturn(Optional.of(bad));
        when(holds.expire(bad.getId(), NOW)).thenThrow(new IllegalStateException("lock wait timeout"));
        givenExpires(good);

        Summary summary = service.expireOverdue(NOW);

        assertThat(summary).isEqualTo(new Summary(2, 1, 1));
        verify(txManager, times(1)).rollback(any()); // the failed hold's own transaction
        verify(txManager, times(1)).commit(any());   // the good hold still committed
        verify(drops, times(1)).releaseUnits(eq(DROP_ID), eq(1), eq(NOW)); // only the good hold's units
    }

    @Test
    void anExpiryRaisesOneExpiredEventAfterTheUnitsAreReturnedAndASkipOrFailureRaisesNone() {
        Hold hold = overdueHold(3);
        when(holds.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(holds.expire(hold.getId(), NOW)).thenReturn(1);
        when(drops.releaseUnits(DROP_ID, 3, NOW)).thenReturn(1);

        service.expireOne(hold.getId(), NOW);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        InOrder order = inOrder(drops, events);
        order.verify(drops).releaseUnits(DROP_ID, 3, NOW);
        order.verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(HoldLifecycleEvent.class, e -> {
            assertThat(e.type()).isEqualTo(HoldLifecycleEvent.Type.HOLD_EXPIRED);
            assertThat(e.type().changesAvailability()).isTrue();
            assertThat(e.status()).isEqualTo(HoldStatus.EXPIRED);
            assertThat(e.dropId()).isEqualTo(DROP_ID);
            assertThat(e.quantity()).isEqualTo(3);
        });

        // nothing more for a skipped hold or for a failed return
        Hold skipped = overdueHold(1);
        when(holds.findById(skipped.getId())).thenReturn(Optional.of(skipped));
        when(holds.expire(skipped.getId(), NOW)).thenReturn(0);
        service.expireOne(skipped.getId(), NOW);
        Hold broken = overdueHold(1);
        when(holds.findById(broken.getId())).thenReturn(Optional.of(broken));
        when(holds.expire(broken.getId(), NOW)).thenReturn(1);
        when(drops.releaseUnits(DROP_ID, 1, NOW)).thenReturn(0);
        assertThatThrownBy(() -> service.expireOne(broken.getId(), NOW)).isInstanceOf(InventoryInvariantViolationException.class);
        verify(events, times(1)).publishEvent(org.mockito.ArgumentMatchers.<Object>any());
    }

    // ---------------------------------------------------------------- helpers

    private void givenPages(List<UUID> first, List<UUID>... rest) {
        var stub = when(holds.findOverdueActiveIds(eq(NOW), any(Pageable.class))).thenReturn(first);
        for (List<UUID> page : rest) {
            stub = stub.thenReturn(page);
        }
    }

    private void givenExpires(Hold hold) {
        when(holds.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(holds.expire(hold.getId(), NOW)).thenReturn(1);
        when(drops.releaseUnits(DROP_ID, hold.getQuantity(), NOW)).thenReturn(1);
    }

    private static Hold overdueHold(int quantity) {
        return Hold.createActive(DROP_ID, "alice", UUID.randomUUID().toString(), quantity, CREATED, Duration.ofMinutes(5));
    }
}
