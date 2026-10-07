package com.kibo.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kibo.reservation.config.KiboProperties;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.exception.DropNotFoundException;
import com.kibo.reservation.domain.exception.DropNotReleasedException;
import com.kibo.reservation.domain.exception.ErrorCode;
import com.kibo.reservation.domain.exception.IdempotencyKeyConflictException;
import com.kibo.reservation.domain.exception.InsufficientInventoryException;
import com.kibo.reservation.domain.exception.InvalidHoldRequestException;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Service-level tests for placing a hold (US1). Repositories and the transaction manager are mocks, so
 * these run without infrastructure; the transaction manager mock lets each test assert the transaction
 * outcome (commit vs rollback) explicitly.
 */
class HoldServiceCreateTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    private static final long DROP_ID = 7L;

    private DropRepository drops;
    private HoldRepository holds;
    private PlatformTransactionManager txManager;
    private ApplicationEventPublisher events;
    private HoldService service;

    @BeforeEach
    void setUp() {
        drops = mock(DropRepository.class);
        holds = mock(HoldRepository.class);
        txManager = mock(PlatformTransactionManager.class);
        events = mock(ApplicationEventPublisher.class);
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        KiboProperties properties = new KiboProperties(
                new KiboProperties.HoldSettings(Duration.ofMinutes(5), 4),
                new KiboProperties.ExpirationSettings(Duration.ofSeconds(2), 200),
                new KiboProperties.CacheSettings(Duration.ofSeconds(3)),
                new KiboProperties.SeedSettings(false));
        service = new HoldService(drops, holds, txManager, Clock.fixed(NOW, ZoneOffset.UTC), properties,
                mock(HoldExpirationService.class), events);
        when(holds.findByCustomerIdAndRequestKey(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void placesAnActiveHoldByDecrementingThenInsertingInOneCommittedTransaction() {
        givenDrop(openDrop(4));
        when(drops.reserveUnits(DROP_ID, 2, NOW)).thenReturn(1);

        HoldPlacement placement = service.placeHold(command("k1", 2));

        assertThat(placement.created()).isTrue();
        Hold hold = placement.hold();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.getDropId()).isEqualTo(DROP_ID);
        assertThat(hold.getQuantity()).isEqualTo(2);
        assertThat(hold.getCustomerId()).isEqualTo("alice");
        assertThat(hold.getRequestKey()).isEqualTo("k1");
        assertThat(hold.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));

        // Decrement happens first, then the hold is inserted (and flushed) ...
        InOrder order = inOrder(txManager, drops, holds);
        order.verify(txManager).getTransaction(any(TransactionDefinition.class));
        order.verify(drops).reserveUnits(DROP_ID, 2, NOW);
        order.verify(holds).saveAndFlush(hold);
        // ... inside exactly one transaction, which commits.
        order.verify(txManager).commit(any());
        verify(txManager, times(1)).getTransaction(any());
        verify(txManager, never()).rollback(any());
    }

    @Test
    void unknownDropIsRejectedWithoutTouchingInventory() {
        when(drops.findById(DROP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.placeHold(command("k1", 1))).isInstanceOf(DropNotFoundException.class);

        verify(drops, never()).reserveUnits(anyLong(), anyInt(), any());
        verify(holds, never()).saveAndFlush(any());
        verify(txManager).rollback(any());
    }

    @Test
    void dropThatHasNotStartedIsRejectedWithoutTouchingInventory() {
        givenDrop(Drop.create("Later", null, 10, 4, NOW.plusSeconds(1), NOW));

        assertThatThrownBy(() -> service.placeHold(command("k1", 1)))
                .isInstanceOf(DropNotReleasedException.class)
                .satisfies(e -> assertThat(((DropNotReleasedException) e).properties())
                        .containsEntry("startsAt", NOW.plusSeconds(1)));

        verify(drops, never()).reserveUnits(anyLong(), anyInt(), any());
        verify(holds, never()).saveAndFlush(any());
    }

    @Test
    void dropIsReleasedExactlyAtItsStartTime() {
        givenDrop(Drop.create("Now", null, 10, 4, NOW, NOW));
        when(drops.reserveUnits(DROP_ID, 1, NOW)).thenReturn(1);

        assertThat(service.placeHold(command("k1", 1)).created()).isTrue();
    }

    @Test
    void quantityAboveTheDropsPerHoldMaximumIsAValidationError() {
        givenDrop(openDrop(4));

        assertThatThrownBy(() -> service.placeHold(command("k1", 5)))
                .isInstanceOf(InvalidHoldRequestException.class)
                .satisfies(e -> assertThat(((InvalidHoldRequestException) e).code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(drops, never()).reserveUnits(anyLong(), anyInt(), any());
    }

    @Test
    void quantityBelowOneIsAValidationError() {
        givenDrop(openDrop(4));

        assertThatThrownBy(() -> service.placeHold(command("k1", 0))).isInstanceOf(InvalidHoldRequestException.class);
        assertThatThrownBy(() -> service.placeHold(command("k2", -3))).isInstanceOf(InvalidHoldRequestException.class);

        verify(drops, never()).reserveUnits(anyLong(), anyInt(), any());
    }

    @Test
    void insufficientInventoryCreatesNoHoldAndRollsBack() {
        givenDrop(openDrop(4));
        when(drops.reserveUnits(DROP_ID, 3, NOW)).thenReturn(0);
        when(drops.findAvailableQuantity(DROP_ID)).thenReturn(Optional.of(2));

        assertThatThrownBy(() -> service.placeHold(command("k1", 3)))
                .isInstanceOf(InsufficientInventoryException.class)
                .satisfies(e -> assertThat(((InsufficientInventoryException) e).availableQuantity()).isEqualTo(2));

        verify(holds, never()).saveAndFlush(any());
        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
    }

    @Test
    void retryWithTheSameKeyReplaysTheOriginalHoldWithoutConsumingInventory() {
        Hold original = Hold.createActive(DROP_ID, "alice", "k1", 2, NOW.minusSeconds(5), Duration.ofMinutes(5));
        when(holds.findByCustomerIdAndRequestKey("alice", "k1")).thenReturn(Optional.of(original));

        HoldPlacement placement = service.placeHold(command("k1", 2));

        assertThat(placement.created()).isFalse();
        assertThat(placement.hold()).isSameAs(original);
        verify(drops, never()).reserveUnits(anyLong(), anyInt(), any());
        verify(holds, never()).saveAndFlush(any());
    }

    @Test
    void sameKeyWithDifferentDetailsIsAConflict() {
        Hold original = Hold.createActive(DROP_ID, "alice", "k1", 2, NOW, Duration.ofMinutes(5));
        when(holds.findByCustomerIdAndRequestKey("alice", "k1")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.placeHold(command("k1", 3))).isInstanceOf(IdempotencyKeyConflictException.class);
        assertThatThrownBy(() -> service.placeHold(new PlaceHoldCommand(8L, "alice", "k1", 2)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        verify(drops, never()).reserveUnits(anyLong(), anyInt(), any());
    }

    @Test
    void aReplayLookupThatReturnsAnotherCustomersHoldIsRefusedAndRevealsNothing() {
        givenDrop(openDrop(4));
        Hold someoneElses = Hold.createActive(DROP_ID, "Alice", "k1", 2, NOW, Duration.ofMinutes(5));
        when(holds.findByCustomerIdAndRequestKey("alice", "k1")).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.placeHold(command("k1", 2)))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(someoneElses.getId().toString())
                        .doesNotContain("Alice"));

        verify(drops, never()).reserveUnits(anyLong(), anyInt(), any());
        verify(holds, never()).saveAndFlush(any());
    }

    @Test
    void zeroRowsButTheKeyWasJustCommittedConcurrentlyReplaysInsteadOfReportingSoldOut() {
        givenDrop(openDrop(4));
        Hold concurrent = Hold.createActive(DROP_ID, "alice", "k1", 1, NOW, Duration.ofMinutes(5));
        when(holds.findByCustomerIdAndRequestKey("alice", "k1"))
                .thenReturn(Optional.empty(), Optional.of(concurrent));
        when(drops.reserveUnits(DROP_ID, 1, NOW)).thenReturn(0);

        HoldPlacement placement = service.placeHold(command("k1", 1));

        assertThat(placement.created()).isFalse();
        assertThat(placement.hold()).isSameAs(concurrent);
        verify(holds, never()).saveAndFlush(any());
    }

    @Test
    void duplicateKeyOnInsertRollsBackTheDecrementAndReplaysTheCommittedHold() {
        givenDrop(openDrop(4));
        when(drops.reserveUnits(DROP_ID, 1, NOW)).thenReturn(1);
        Hold winner = Hold.createActive(DROP_ID, "alice", "k1", 1, NOW, Duration.ofMinutes(5));
        when(holds.findByCustomerIdAndRequestKey("alice", "k1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(holds.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_holds_customer_request"));

        HoldPlacement placement = service.placeHold(command("k1", 1));

        assertThat(placement.created()).isFalse();
        assertThat(placement.hold()).isSameAs(winner);
        // First transaction (decrement + failed insert) rolled back; second (read-only replay) committed.
        verify(txManager, times(2)).getTransaction(any());
        verify(txManager, times(1)).rollback(any());
        verify(txManager, times(1)).commit(any());
    }

    @Test
    void otherIntegrityFailuresOnInsertAreNotMistakenForReplays() {
        givenDrop(openDrop(4));
        when(drops.reserveUnits(DROP_ID, 1, NOW)).thenReturn(1);
        when(holds.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("something else"));

        assertThatThrownBy(() -> service.placeHold(command("k1", 1))).isInstanceOf(DataIntegrityViolationException.class);
        verify(txManager).rollback(any());
    }

    @Test
    void insertedHoldIsTheOneReturned() {
        givenDrop(openDrop(4));
        when(drops.reserveUnits(DROP_ID, 1, NOW)).thenReturn(1);

        HoldPlacement placement = service.placeHold(command("k1", 1));

        ArgumentCaptor<Hold> saved = ArgumentCaptor.forClass(Hold.class);
        verify(holds).saveAndFlush(saved.capture());
        assertThat(placement.hold()).isSameAs(saved.getValue());
        assertThat(saved.getValue().isNew()).isTrue();
    }

    @Test
    void aCreatedHoldRaisesOneLifecycleEventInsideTheTransactionAfterTheInsert() {
        givenDrop(openDrop(4));
        when(drops.reserveUnits(DROP_ID, 2, NOW)).thenReturn(1);

        HoldPlacement placement = service.placeHold(command("k1", 2));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        InOrder order = inOrder(holds, events, txManager);
        order.verify(holds).saveAndFlush(placement.hold());
        order.verify(events).publishEvent(event.capture());
        order.verify(txManager).commit(any()); // raised before commit; listeners wait for the commit
        assertThat(event.getValue()).isInstanceOfSatisfying(HoldLifecycleEvent.class, e -> {
            assertThat(e.type()).isEqualTo(HoldLifecycleEvent.Type.HOLD_CREATED);
            assertThat(e.holdId()).isEqualTo(placement.hold().getId());
            assertThat(e.dropId()).isEqualTo(DROP_ID);
            assertThat(e.quantity()).isEqualTo(2);
            assertThat(e.status()).isEqualTo(HoldStatus.ACTIVE);
        });
    }

    @Test
    void replaysAndRejectedRequestsRaiseNoEvent() {
        // replay of an earlier hold
        Hold earlier = Hold.createActive(DROP_ID, "alice", "k1", 1, NOW.minusSeconds(5), Duration.ofMinutes(5));
        when(holds.findByCustomerIdAndRequestKey("alice", "k1")).thenReturn(Optional.of(earlier));
        service.placeHold(command("k1", 1));

        // insufficient inventory
        givenDrop(openDrop(4));
        when(holds.findByCustomerIdAndRequestKey("alice", "k2")).thenReturn(Optional.empty());
        when(drops.reserveUnits(DROP_ID, 1, NOW)).thenReturn(0);
        assertThatThrownBy(() -> service.placeHold(command("k2", 1))).isInstanceOf(InsufficientInventoryException.class);

        verifyNoInteractions(events);
    }

    private void givenDrop(Drop drop) {
        ReflectionTestUtils.setField(drop, "id", DROP_ID);
        when(drops.findById(DROP_ID)).thenReturn(Optional.of(drop));
    }

    private static Drop openDrop(int maxPerHold) {
        return Drop.create("Open", null, 10, maxPerHold, NOW.minusSeconds(60), NOW.minusSeconds(120));
    }

    private static PlaceHoldCommand command(String key, int quantity) {
        return new PlaceHoldCommand(DROP_ID, "alice", key, quantity);
    }
}
