package com.kibo.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kibo.reservation.cache.DropCache;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.exception.DropNotFoundException;
import com.kibo.reservation.domain.exception.ErrorCode;
import com.kibo.reservation.repository.DropRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Cache-aside behavior of the drop queries. DropCache and the repository are mocks: no Redis, no MySQL. */
class DropQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    private DropRepository drops;
    private DropCache cache;
    private PlatformTransactionManager txManager;
    private DropQueryService service;

    @BeforeEach
    void setUp() {
        drops = mock(DropRepository.class);
        cache = mock(DropCache.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(cache.findAll()).thenReturn(Optional.empty());
        when(cache.findDrop(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.empty());
        service = new DropQueryService(drops, cache, txManager);
    }

    @Test
    void listMissReadsMySqlInOrderInAReadOnlyTransactionThenCachesTheResult() {
        when(drops.findAllByOrderByStartsAtAscIdAsc()).thenReturn(List.of(drop(1L, "First", 10), drop(2L, "Second", 1)));

        List<DropSnapshot> result = service.listDrops();

        assertThat(result).extracting(DropSnapshot::id).containsExactly(1L, 2L);
        assertThat(result.get(0)).isEqualTo(new DropSnapshot(1L, "First", "desc", 10, 10, 4, NOW));
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        InOrder order = inOrder(cache, drops);
        order.verify(cache).findAll();
        order.verify(drops).findAllByOrderByStartsAtAscIdAsc();
        order.verify(cache).putAll(result);
    }

    @Test
    void listHitIsServedFromTheCacheWithoutTouchingMySql() {
        List<DropSnapshot> cached = List.of(new DropSnapshot(1L, "Cached", null, 5, 4, 4, NOW));
        when(cache.findAll()).thenReturn(Optional.of(cached));

        assertThat(service.listDrops()).isSameAs(cached);

        verifyNoInteractions(drops, txManager);
        verify(cache, never()).putAll(any());
    }

    @Test
    void anEmptyListIsCachedToo() {
        when(drops.findAllByOrderByStartsAtAscIdAsc()).thenReturn(List.of());

        assertThat(service.listDrops()).isEmpty();

        verify(cache).putAll(List.of());
    }

    @Test
    void getMissReadsMySqlThenCachesTheSnapshot() {
        when(drops.findById(7L)).thenReturn(Optional.of(drop(7L, "Seven", 3)));

        DropSnapshot result = service.getDrop(7L);

        assertThat(result.name()).isEqualTo("Seven");
        verify(cache).putDrop(result);
    }

    @Test
    void getHitIsServedFromTheCacheWithoutTouchingMySql() {
        DropSnapshot cached = new DropSnapshot(7L, "Cached", null, 5, 4, 4, NOW);
        when(cache.findDrop(7L)).thenReturn(Optional.of(cached));

        assertThat(service.getDrop(7L)).isSameAs(cached);

        verifyNoInteractions(drops, txManager);
        verify(cache, never()).putDrop(any());
    }

    @Test
    void unknownDropFailsWithDropNotFoundAndIsNeverCached() {
        when(drops.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDrop(99L))
                .isInstanceOfSatisfying(DropNotFoundException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.DROP_NOT_FOUND))
                .hasMessageContaining("99");

        verify(cache, never()).putDrop(any());
    }

    private static Drop drop(long id, String name, int total) {
        Drop drop = Drop.create(name, "desc", total, 4, NOW, NOW);
        ReflectionTestUtils.setField(drop, "id", id); // normally assigned by the database
        return drop;
    }
}
