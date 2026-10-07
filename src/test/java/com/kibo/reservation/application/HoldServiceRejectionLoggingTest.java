package com.kibo.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kibo.reservation.config.KiboProperties;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.exception.HoldNotFoundException;
import com.kibo.reservation.domain.exception.IdempotencyKeyConflictException;
import com.kibo.reservation.domain.exception.InsufficientInventoryException;
import com.kibo.reservation.domain.exception.InvalidStateTransitionException;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** FR-031: every rejected hold request is logged with customerId, dropId, quantity (where known) and the error code. */
class HoldServiceRejectionLoggingTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    private static final long DROP_ID = 7L;

    private DropRepository drops;
    private HoldRepository holds;
    private HoldService service;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        drops = mock(DropRepository.class);
        holds = mock(HoldRepository.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        KiboProperties properties = new KiboProperties(
                new KiboProperties.HoldSettings(Duration.ofMinutes(5), 4),
                new KiboProperties.ExpirationSettings(Duration.ofSeconds(2), 200),
                new KiboProperties.CacheSettings(Duration.ofSeconds(3)),
                new KiboProperties.SeedSettings(false));
        service = new HoldService(drops, holds, tx, Clock.fixed(NOW, ZoneOffset.UTC), properties,
                mock(HoldExpirationService.class), mock(ApplicationEventPublisher.class));
        when(holds.findByCustomerIdAndRequestKey(any(), any())).thenReturn(Optional.empty());
        serviceLogger = (Logger) LoggerFactory.getLogger(HoldService.class);
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    @Test
    void anInsufficientInventoryRejectionIsLoggedWithCustomerDropQuantityAndCode() {
        when(drops.findById(DROP_ID)).thenReturn(Optional.of(Drop.create("Open", null, 5, 4, NOW.minusSeconds(60), NOW)));
        when(drops.reserveUnits(DROP_ID, 3, NOW)).thenReturn(0);
        when(drops.findAvailableQuantity(DROP_ID)).thenReturn(Optional.of(1));

        assertThatThrownBy(() -> service.placeHold(new PlaceHoldCommand(DROP_ID, "alice", "k1", 3)))
                .isInstanceOf(InsufficientInventoryException.class);

        assertThat(lastRejection()).containsEntry("customerId", "alice").containsEntry("dropId", String.valueOf(DROP_ID))
                .containsEntry("quantity", "3").containsEntry("code", "INSUFFICIENT_INVENTORY")
                .doesNotContainValue("k1");
    }

    @Test
    void anIdempotencyKeyConflictFoundWhileReplayingAfterADuplicateKeyIsAlsoLogged() {
        when(drops.findById(DROP_ID)).thenReturn(Optional.of(Drop.create("Open", null, 5, 4, NOW.minusSeconds(60), NOW)));
        when(drops.reserveUnits(DROP_ID, 2, NOW)).thenReturn(1);
        when(holds.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Duplicate entry"));
        // The concurrent winner used the same key for a DIFFERENT quantity.
        Hold winner = Hold.createActive(DROP_ID, "alice", "k1", 1, NOW, Duration.ofMinutes(5));
        when(holds.findByCustomerIdAndRequestKey("alice", "k1"))
                .thenReturn(Optional.empty())   // first lookup, before the insert
                .thenReturn(Optional.of(winner)); // lookup after the duplicate-key failure

        assertThatThrownBy(() -> service.placeHold(new PlaceHoldCommand(DROP_ID, "alice", "k1", 2)))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        assertThat(lastRejection()).containsEntry("customerId", "alice").containsEntry("dropId", String.valueOf(DROP_ID))
                .containsEntry("quantity", "2").containsEntry("code", "IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void aRejectedConfirmOrCancelOfAnOwnedHoldIsLoggedWithDropQuantityAndCode() {
        Hold hold = Hold.createActive(DROP_ID, "alice", "k1", 3, NOW.minusSeconds(60), Duration.ofMinutes(5));
        ReflectionTestUtils.setField(hold, "status", HoldStatus.CANCELLED);
        when(holds.findById(hold.getId())).thenAnswer(inv -> Optional.of(hold));
        when(holds.moveBeforeExpiry(any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(hold.getId(), "alice")).isInstanceOf(InvalidStateTransitionException.class);

        assertThat(lastRejection()).containsEntry("customerId", "alice").containsEntry("dropId", String.valueOf(DROP_ID))
                .containsEntry("quantity", "3").containsEntry("code", "INVALID_STATE_TRANSITION")
                .containsEntry("holdId", hold.getId().toString());
    }

    @Test
    void aRejectedTransitionOfSomeoneElsesOrAnUnknownHoldRevealsNothingAboutTheHold() {
        Hold hold = Hold.createActive(DROP_ID, "alice", "k1", 3, NOW, Duration.ofMinutes(5));
        when(holds.findById(hold.getId())).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.cancel(hold.getId(), "mallory")).isInstanceOf(HoldNotFoundException.class);

        Map<String, Object> entry = lastRejection();
        assertThat(entry).containsEntry("customerId", "mallory").containsEntry("code", "HOLD_NOT_FOUND")
                .doesNotContainKeys("dropId", "quantity");
        assertThat(entry.values()).doesNotContain("alice");
    }

    private Map<String, Object> lastRejection() {
        ILoggingEvent event = logs.list.stream().filter(e -> e.getFormattedMessage().contains("rejected"))
                .reduce((first, second) -> second).orElseThrow(() -> new AssertionError("nothing logged: " + logs.list));
        Map<String, Object> values = new HashMap<>();
        // Values compared as text: that is how they appear in the log (enums and UUIDs print as their name / id).
        event.getKeyValuePairs().forEach(pair -> values.put(pair.key, String.valueOf(pair.value)));
        return values;
    }
}
