package com.kibo.reservation.application;

import com.kibo.reservation.cache.DropCache;
import com.kibo.reservation.domain.exception.DropNotFoundException;
import com.kibo.reservation.repository.DropRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drop queries (US6, FR-001, FR-002): cache-aside over MySQL.
 *
 * <p>Read path: try Redis; on a miss (or any Redis problem) read MySQL in a short read-only transaction,
 * then offer the result to the cache. The database transaction is opened only on a miss, and never while
 * talking to Redis. Unknown drops are never cached.
 *
 * <p>What is returned here is informational and can lag MySQL by up to the cache TTL (3 s by default; see
 * docs/caching-redis.md). Hold decisions never use it: they rely on the atomic conditional update against
 * MySQL (Constitution III, V).
 */
@Service
public class DropQueryService {

    private final DropRepository drops;
    private final DropCache cache;
    private final TransactionTemplate readTransaction;

    public DropQueryService(DropRepository drops, DropCache cache, PlatformTransactionManager transactionManager) {
        this.drops = drops;
        this.cache = cache;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public List<DropSnapshot> listDrops() {
        Optional<List<DropSnapshot>> cached = cache.findAll();
        if (cached.isPresent()) {
            return cached.get();
        }
        List<DropSnapshot> loaded = readTransaction.execute(
                status -> drops.findAllByOrderByStartsAtAscIdAsc().stream().map(DropSnapshot::of).toList());
        cache.putAll(loaded);
        return loaded;
    }

    public DropSnapshot getDrop(long dropId) {
        Optional<DropSnapshot> cached = cache.findDrop(dropId);
        if (cached.isPresent()) {
            return cached.get();
        }
        DropSnapshot loaded = readTransaction.execute(status -> drops.findById(dropId).map(DropSnapshot::of))
                .orElseThrow(() -> new DropNotFoundException(dropId));
        cache.putDrop(loaded);
        return loaded;
    }
}
