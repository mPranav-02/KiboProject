package com.kibo.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kibo.reservation.config.KiboProperties;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.exception.HoldNotFoundException;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** US5: a customer reads their own hold. Read-only, owner-only, no settling. No infrastructure. */
class HoldServiceGetTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    private DropRepository drops;
    private HoldRepository holds;
    private PlatformTransactionManager txManager;
    private HoldExpirationService expiration;
    private HoldService service;
    private Hold hold;

    @BeforeEach
    void setUp() {
        drops = mock(DropRepository.class);
        holds = mock(HoldRepository.class);
        txManager = mock(PlatformTransactionManager.class);
        expiration = mock(HoldExpirationService.class);
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        KiboProperties properties = new KiboProperties(
                new KiboProperties.HoldSettings(Duration.ofMinutes(5), 4),
                new KiboProperties.ExpirationSettings(Duration.ofSeconds(2), 200),
                new KiboProperties.CacheSettings(Duration.ofSeconds(3)),
                new KiboProperties.SeedSettings(false));
        service = new HoldService(drops, holds, txManager, Clock.fixed(NOW, ZoneOffset.UTC), properties, expiration,
                mock(ApplicationEventPublisher.class));
        hold = Hold.createActive(7L, "alice", "k1", 2, NOW.minusSeconds(60), Duration.ofMinutes(5));
        when(holds.findById(hold.getId())).thenReturn(Optional.of(hold));
    }

    @Test
    void theOwnerGetsTheirHoldInAReadOnlyTransactionWithoutChangingAnything() {
        Hold found = service.getHold(hold.getId(), "alice");

        assertThat(found).isSameAs(hold);
        assertThat(found.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        org.mockito.ArgumentCaptor<TransactionDefinition> definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        verifyNoInteractions(drops, expiration);
        verify(holds, never()).moveAtOrAfterExpiry(any(), any(), any(), any());
    }

    @Test
    void anOverdueActiveHoldIsReadAsExpiredButIsNotSettledByTheRead() {
        Hold overdue = Hold.createActive(7L, "alice", "k2", 2, NOW.minus(Duration.ofMinutes(10)), Duration.ofMinutes(5));
        when(holds.findById(overdue.getId())).thenReturn(Optional.of(overdue));

        Hold found = service.getHold(overdue.getId(), "alice");

        assertThat(found.effectiveStatus(NOW)).isEqualTo(HoldStatus.EXPIRED);
        assertThat(found.getStatus()).as("stored status is untouched").isEqualTo(HoldStatus.ACTIVE);
        verifyNoInteractions(expiration);
        verify(holds, never()).moveAtOrAfterExpiry(any(), any(), any(), any());
    }

    @Test
    void anotherCustomersHoldIsNotFoundEvenWhenOnlyTheCaseDiffers() {
        for (String other : new String[] {"mallory", "Alice", "ALICE", "alice "}) {
            assertThatThrownBy(() -> service.getHold(hold.getId(), other)).as(other)
                    .isInstanceOf(HoldNotFoundException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("alice"));
        }
    }

    @Test
    void anUnknownHoldIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(holds.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getHold(unknown, "alice")).isInstanceOf(HoldNotFoundException.class);
    }
}
