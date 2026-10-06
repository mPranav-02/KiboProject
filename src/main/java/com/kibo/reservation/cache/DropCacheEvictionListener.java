package com.kibo.reservation.cache;

import com.kibo.reservation.domain.event.HoldLifecycleEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts a drop's cached read model once the transaction that changed its availability has COMMITTED.
 *
 * <p>After commit, never before: evicting earlier would let a concurrent reader re-cache the old value
 * from MySQL and keep it. Rolled-back work raises no callback, so nothing is evicted for it. A failed or
 * lost eviction is tolerated: the entry's short TTL bounds how stale it can get (docs/caching-redis.md).
 *
 * <p>Confirming does not change availability (the units stay consumed), so it does not evict.
 */
@Component
public class DropCacheEvictionListener {

    private final DropCache cache;

    public DropCacheEvictionListener(DropCache cache) {
        this.cache = cache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHoldChanged(HoldLifecycleEvent event) {
        if (event.type().changesAvailability()) {
            cache.evict(event.dropId());
        }
    }
}
