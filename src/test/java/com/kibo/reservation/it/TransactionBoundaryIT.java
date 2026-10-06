package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.exception.InsufficientInventoryException;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import com.kibo.reservation.domain.Hold;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit verification of the transaction boundary around hold creation (research.md §4):
 * <ul>
 *   <li>the inventory decrement and the hold INSERT run in one open, read-write transaction (the decrement
 *       is visible inside it but not yet to other connections when the INSERT happens);</li>
 *   <li>a failure after the decrement rolls the decrement back;</li>
 *   <li>a rejected request changes nothing at all.</li>
 * </ul>
 */
class TransactionBoundaryIT extends AbstractMySqlIT {

    @Autowired
    private HoldService holdService;

    @MockitoSpyBean
    private DropRepository drops;

    @MockitoSpyBean
    private HoldRepository holds;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long dropId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
        Instant now = clock.instant();
        dropId = drops.save(Drop.create("Tx", null, 10, 4, now.minusSeconds(60), now)).getId();
    }

    @AfterEach
    void resetSpies() {
        reset(drops, holds);
    }

    @Test
    void decrementAndInsertRunInTheSameUncommittedTransaction() throws Exception {
        List<String> observations = new ArrayList<>();
        doAnswer(inv -> {
            // We are at the hold INSERT. The decrement already ran in this transaction:
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            observations.add("inside=" + availableInsideCurrentTransaction());   // sees its own decrement
            observations.add("outside=" + availableFromAnIndependentConnection()); // not committed yet
            // Perform the real insert through the same transactional EntityManager.
            Hold hold = inv.getArgument(0);
            entityManager.persist(hold);
            entityManager.flush();
            return hold;
        }).when(holds).saveAndFlush(any());

        holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2));

        // Inside the transaction the decrement was visible; to everyone else it was not yet committed.
        // Both statements therefore belonged to one open transaction, which then committed both together.
        assertThat(observations).containsExactly("inside=8", "outside=10");
        assertThat(available()).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds WHERE customer_id = 'alice'", Integer.class)).isEqualTo(1);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void failureAfterTheDecrementRollsTheDecrementBack() {
        doAnswer(inv -> {
            // The decrement has already executed in this transaction at this point.
            assertThat(availableInsideCurrentTransaction()).isEqualTo(8);
            throw new IllegalStateException("simulated failure while inserting the hold");
        }).when(holds).saveAndFlush(any());

        assertThatThrownBy(() -> holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(available()).as("decrement rolled back").isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds", Integer.class)).isZero();
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void insufficientInventoryChangesNothing() {
        jdbc.update("UPDATE drops SET available_quantity = 1 WHERE id = ?", dropId);
        jdbc.update("""
                INSERT INTO holds (id, drop_id, customer_id, request_key, quantity, status, expires_at, created_at, updated_at)
                VALUES (UUID(), ?, 'seed', 'seed', 9, 'CONFIRMED', NOW(6), NOW(6), NOW(6))
                """, dropId);
        Object updatedAtBefore = jdbc.queryForObject("SELECT updated_at FROM drops WHERE id = ?", Object.class, dropId);

        assertThatThrownBy(() -> holdService.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2)))
                .isInstanceOf(InsufficientInventoryException.class);

        assertThat(available()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM drops WHERE id = ?", Object.class, dropId))
                .isEqualTo(updatedAtBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds WHERE customer_id = 'alice'", Integer.class)).isZero();
        assertInventoryInvariant(jdbc, dropId);
    }

    /** A brand-new pooled connection outside the current transaction: sees committed data only. */
    private int availableFromAnIndependentConnection() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT available_quantity FROM drops WHERE id = ?")) {
            ps.setLong(1, dropId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Reads through the current transaction's connection (sees its own uncommitted changes). */
    private int availableInsideCurrentTransaction() {
        return drops.findAvailableQuantity(dropId).orElseThrow();
    }

    private int available() {
        return jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId);
    }
}
