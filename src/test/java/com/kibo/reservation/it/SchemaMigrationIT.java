package com.kibo.reservation.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the foundation against real MySQL:
 * <ul>
 *   <li>Flyway V1 applies, and Hibernate {@code ddl-auto=validate} accepts the entity mappings
 *       (the Spring context would not start otherwise);</li>
 *   <li>entities round-trip without losing precision (UUID as CHAR(36), Instant as DATETIME(6));</li>
 *   <li>the database itself rejects states that would break the inventory invariant.</li>
 * </ul>
 */
class SchemaMigrationIT extends AbstractMySqlIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
    }

    @Test
    void flywayAppliedVersion1() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void dropAndHoldRoundTripThroughJpa() {
        Instant now = clock.instant();
        Drop drop = Drop.create("Sneaker", "Limited run", 50, 4, now.minusSeconds(60), now);
        tx.executeWithoutResult(s -> entityManager.persist(drop));
        Hold hold = Hold.createActive(drop.getId(), "alice", "req-1", 2, now, Duration.ofMinutes(5));
        tx.executeWithoutResult(s -> entityManager.persist(hold));
        entityManager.clear();

        Drop loadedDrop = tx.execute(s -> entityManager.find(Drop.class, drop.getId()));
        Hold loadedHold = tx.execute(s -> entityManager.find(Hold.class, hold.getId()));

        assertThat(loadedDrop.getAvailableQuantity()).isEqualTo(50);
        assertThat(loadedDrop.getTotalQuantity()).isEqualTo(50);
        assertThat(loadedDrop.getStartsAt()).isEqualTo(drop.getStartsAt());
        assertThat(loadedHold.getId()).isEqualTo(hold.getId());
        assertThat(loadedHold.isNew()).isFalse();
        assertThat(loadedHold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(loadedHold.getQuantity()).isEqualTo(2);
        // Microsecond clock => stored expiry equals the in-memory expiry exactly (same boundary in Java and SQL).
        assertThat(loadedHold.getExpiresAt()).isEqualTo(hold.getExpiresAt());
        assertThat(jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, hold.getId().toString()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void availableQuantityCannotGoNegative() {
        long dropId = insertDrop(5, 5);
        assertThatThrownBy(() -> jdbc.update("UPDATE drops SET available_quantity = -1 WHERE id = ?", dropId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ck_drops_available_range");
    }

    @Test
    void availableQuantityCannotExceedTotal() {
        long dropId = insertDrop(5, 5);
        assertThatThrownBy(() -> jdbc.update("UPDATE drops SET available_quantity = 6 WHERE id = ?", dropId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ck_drops_available_range");
    }

    @Test
    void totalQuantityMustBePositive() {
        assertThatThrownBy(() -> insertDrop(0, 0))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ck_drops_total_positive");
    }

    @Test
    void holdStatusIsRestrictedToTheFourLifecycleStates() {
        long dropId = insertDrop(5, 5);
        assertThatThrownBy(() -> insertHold(dropId, "alice", "k1", 1, "PENDING"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ck_holds_status");
    }

    @Test
    void holdQuantityMustBeAtLeastOne() {
        long dropId = insertDrop(5, 5);
        assertThatThrownBy(() -> insertHold(dropId, "alice", "k1", 0, "ACTIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ck_holds_quantity");
    }

    @Test
    void requestKeyIsUniquePerCustomer() {
        long dropId = insertDrop(5, 5);
        insertHold(dropId, "alice", "k1", 1, "ACTIVE");
        assertThatThrownBy(() -> insertHold(dropId, "alice", "k1", 1, "ACTIVE"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasStackTraceContaining("uk_holds_customer_request");
        // The same key from a different customer is independent.
        insertHold(dropId, "bob", "k1", 1, "ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds", Integer.class)).isEqualTo(2);
    }

    @Test
    void holdMustReferenceAnExistingDrop() {
        assertThatThrownBy(() -> insertHold(999_999L, "alice", "k1", 1, "ACTIVE"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("fk_holds_drop");
    }

    private long insertDrop(int total, int available) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO drops (name, total_quantity, available_quantity, max_per_hold, starts_at, created_at, updated_at)
                    VALUES ('test drop', ?, ?, 4, NOW(6), NOW(6), NOW(6))
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, total);
            ps.setInt(2, available);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void insertHold(long dropId, String customer, String key, int quantity, String status) {
        jdbc.update("""
                INSERT INTO holds (id, drop_id, customer_id, request_key, quantity, status, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW(6) + INTERVAL 5 MINUTE, NOW(6), NOW(6))
                """, UUID.randomUUID().toString(), dropId, customer, key, quantity, status);
    }
}
