package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kibo.reservation.api.ApiHeaders;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.exception.HoldExpiredException;
import com.kibo.reservation.domain.exception.HoldNotFoundException;
import com.kibo.reservation.domain.exception.InvalidStateTransitionException;
import com.kibo.reservation.repository.DropRepository;
import com.kibo.reservation.repository.HoldRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US2 + US4 against real MySQL: confirm keeps the units consumed, cancel returns them exactly once,
 * invalid / repeated / expired requests change nothing. Every test ends by checking the inventory invariant.
 * Concurrent races are in {@link ConfirmCancelRaceIT}.
 */
class ConfirmCancelIT extends AbstractMySqlIT {

    private static final int TOTAL = 10;

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldRepository holds;

    @Autowired
    private DropRepository drops;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mvc;

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
        dropId = drops.save(Drop.create("Drop", null, TOTAL, 4, now.minusSeconds(60), now)).getId();
    }

    @Test
    void confirmKeepsTheUnitsConsumed() {
        UUID id = place("alice", "k1", 3);
        assertThat(available()).isEqualTo(TOTAL - 3);

        Hold confirmed = holdService.confirm(id, "alice");

        assertThat(confirmed.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
        assertThat(confirmed.getResolvedAt()).isNotNull();
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
        assertThat(available()).as("confirm must not return inventory").isEqualTo(TOTAL - 3);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void cancelReturnsTheUnitsOnce() {
        UUID id = place("alice", "k1", 3);
        assertThat(available()).isEqualTo(TOTAL - 3);

        Hold cancelled = holdService.cancel(id, "alice");

        assertThat(cancelled.getStatus()).isEqualTo(HoldStatus.CANCELLED);
        assertThat(cancelled.getResolvedAt()).isNotNull();
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(available()).isEqualTo(TOTAL);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void repeatedConfirmIsIdempotentAndChangesNothing() {
        UUID id = place("alice", "k1", 3);
        Hold first = holdService.confirm(id, "alice");
        Instant resolvedAt = first.getResolvedAt();
        Instant updatedAt = holds.findById(id).orElseThrow().getUpdatedAt();

        for (int i = 0; i < 5; i++) {
            Hold again = holdService.confirm(id, "alice");
            assertThat(again.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
            assertThat(again.getResolvedAt()).isEqualTo(resolvedAt);
        }

        assertThat(holds.findById(id).orElseThrow().getUpdatedAt()).as("row untouched").isEqualTo(updatedAt);
        assertThat(available()).isEqualTo(TOTAL - 3);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void repeatedCancelIsIdempotentAndReturnsTheUnitsOnlyOnce() {
        UUID id = place("alice", "k1", 3);
        Hold first = holdService.cancel(id, "alice");

        for (int i = 0; i < 5; i++) {
            Hold again = holdService.cancel(id, "alice");
            assertThat(again.getStatus()).isEqualTo(HoldStatus.CANCELLED);
            assertThat(again.getResolvedAt()).isEqualTo(first.getResolvedAt());
        }

        assertThat(available()).as("units returned once, not once per request").isEqualTo(TOTAL);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void cancellingAConfirmedHoldIsRejectedAndTheUnitsStayConsumed() {
        UUID id = place("alice", "k1", 3);
        holdService.confirm(id, "alice");

        assertThatThrownBy(() -> holdService.cancel(id, "alice"))
                .isInstanceOfSatisfying(InvalidStateTransitionException.class,
                        e -> assertThat(e.currentStatus()).isEqualTo(HoldStatus.CONFIRMED));

        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
        assertThat(available()).isEqualTo(TOTAL - 3);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void confirmingACancelledHoldIsRejectedAndTheUnitsAreNotTakenAgain() {
        UUID id = place("alice", "k1", 3);
        holdService.cancel(id, "alice");

        assertThatThrownBy(() -> holdService.confirm(id, "alice"))
                .isInstanceOfSatisfying(InvalidStateTransitionException.class,
                        e -> assertThat(e.currentStatus()).isEqualTo(HoldStatus.CANCELLED));

        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(available()).isEqualTo(TOTAL);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void anOverdueActiveHoldCanNeitherBeConfirmedNorCancelledAndIsSettledOnContact() {
        UUID id = place("alice", "k1", 3);
        makeOverdue(id);

        assertThatThrownBy(() -> holdService.confirm(id, "alice")).isInstanceOf(HoldExpiredException.class);
        assertThatThrownBy(() -> holdService.cancel(id, "alice")).isInstanceOf(HoldExpiredException.class);

        // The rejected request settled the hold: EXPIRED, units back exactly once (the second request was a no-op)
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        assertThat(available()).isEqualTo(TOTAL);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void theExpiryBoundaryIsExclusiveInTheDatabaseGuard() {
        UUID id = place("alice", "k1", 3);
        Instant expiresAt = holds.findById(id).orElseThrow().getExpiresAt();
        // The guarded updates must run inside a transaction; the service always provides one.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThat(confirmAt(tx, id, expiresAt)).as("at exactly expiresAt").isZero();
        assertThat(cancelAt(tx, id, expiresAt)).as("at exactly expiresAt").isZero();
        assertThat(confirmAt(tx, id, expiresAt.plusNanos(1_000))).as("after expiresAt").isZero();
        assertThat(statusOf(id)).isEqualTo("ACTIVE");

        assertThat(confirmAt(tx, id, expiresAt.minusNanos(1_000)))
                .as("1 microsecond before").isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
    }

    @Test
    void anotherCustomerCannotConfirmOrCancelAndNothingChanges() {
        UUID id = place("alice", "k1", 3);

        assertThatThrownBy(() -> holdService.confirm(id, "mallory")).isInstanceOf(HoldNotFoundException.class);
        assertThatThrownBy(() -> holdService.cancel(id, "mallory")).isInstanceOf(HoldNotFoundException.class);
        assertThatThrownBy(() -> holdService.cancel(UUID.randomUUID(), "alice")).isInstanceOf(HoldNotFoundException.class);

        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        assertThat(available()).isEqualTo(TOTAL - 3);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void cancelledUnitsCanBeReservedAgainButNeverMoreThanTheTotal() {
        UUID big = place("alice", "k1", 4);
        place("bob", "k2", 4);
        place("carol", "k3", 2);
        assertThat(available()).isZero();

        holdService.cancel(big, "alice");
        holdService.cancel(big, "alice"); // repeat must not mint extra units
        assertThat(available()).isEqualTo(4);

        place("dave", "k4", 4);
        assertThat(available()).isZero();
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        UUID cancelMe = place("alice", "k1", 2);
        UUID confirmMe = place("alice", "k2", 3);

        mvc.perform(post("/api/v1/holds/{id}/confirm", confirmMe).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.resolvedAt").exists());
        mvc.perform(post("/api/v1/holds/{id}/confirm", confirmMe).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(post("/api/v1/holds/{id}/cancel", confirmMe).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("CONFIRMED"));

        mvc.perform(post("/api/v1/holds/{id}/cancel", cancelMe).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/holds/{id}/cancel", cancelMe).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/holds/{id}/confirm", cancelMe).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));

        mvc.perform(post("/api/v1/holds/{id}/confirm", cancelMe).header(ApiHeaders.CUSTOMER_ID, "mallory"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        mvc.perform(post("/api/v1/holds/nope/cancel").header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));

        makeOverdue(place("bob", "k3", 1));
        UUID overdue = UUID.fromString(jdbc.queryForObject(
                "SELECT id FROM holds WHERE customer_id = 'bob'", String.class));
        mvc.perform(post("/api/v1/holds/{id}/confirm", overdue).header(ApiHeaders.CUSTOMER_ID, "bob"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));

        // 2 cancelled and returned; 1 overdue hold expired on contact and returned; only the 3 confirmed stay consumed
        assertThat(available()).isEqualTo(TOTAL - 3);
        assertInventoryInvariant(jdbc, dropId);
    }

    // ---------------------------------------------------------------- helpers

    private UUID place(String customer, String key, int quantity) {
        return holdService.placeHold(new PlaceHoldCommand(dropId, customer, key, quantity)).hold().getId();
    }

    private void makeOverdue(UUID holdId) {
        jdbc.update("UPDATE holds SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", holdId.toString());
    }

    private int confirmAt(TransactionTemplate tx, UUID id, Instant now) {
        return tx.execute(s -> holds.confirm(id, "alice", now));
    }

    private int cancelAt(TransactionTemplate tx, UUID id, Instant now) {
        return tx.execute(s -> holds.cancel(id, "alice", now));
    }

    private int available() {
        return jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId);
    }

    private String statusOf(UUID holdId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT status FROM holds WHERE id = ?", holdId.toString());
        return (String) row.get("status");
    }
}
