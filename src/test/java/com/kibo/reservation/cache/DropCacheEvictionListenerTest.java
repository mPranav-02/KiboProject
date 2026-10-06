package com.kibo.reservation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import com.kibo.reservation.domain.event.HoldLifecycleEvent.Type;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class DropCacheEvictionListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    private final DropCache cache = mock(DropCache.class);
    private final DropCacheEvictionListener listener = new DropCacheEvictionListener(cache);

    @Test
    void evictsTheDropWhenAvailabilityChanged() {
        for (Type type : new Type[] {Type.HOLD_CREATED, Type.HOLD_CANCELLED, Type.HOLD_EXPIRED}) {
            listener.onHoldChanged(event(type));
        }

        verify(cache, org.mockito.Mockito.times(3)).evict(7L);
    }

    @Test
    void doesNotEvictOnConfirmBecauseAvailabilityIsUnchanged() {
        listener.onHoldChanged(event(Type.HOLD_CONFIRMED));

        verifyNoInteractions(cache);
    }

    @Test
    void runsOnlyAfterTheTransactionCommitted() throws Exception {
        TransactionalEventListener annotation = DropCacheEvictionListener.class
                .getMethod("onHoldChanged", HoldLifecycleEvent.class).getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).as("never for work outside a committed transaction").isFalse();
        verify(cache, never()).evict(7L);
    }

    @Test
    void onlyCreateCancelAndExpireChangeAvailability() {
        assertThat(Type.HOLD_CREATED.changesAvailability()).isTrue();
        assertThat(Type.HOLD_CANCELLED.changesAvailability()).isTrue();
        assertThat(Type.HOLD_EXPIRED.changesAvailability()).isTrue();
        assertThat(Type.HOLD_CONFIRMED.changesAvailability()).isFalse();
    }

    private static HoldLifecycleEvent event(Type type) {
        Hold hold = Hold.createActive(7L, "alice", "k", 2, NOW, Duration.ofMinutes(5));
        return HoldLifecycleEvent.of(type, hold, HoldStatus.ACTIVE, NOW);
    }
}
