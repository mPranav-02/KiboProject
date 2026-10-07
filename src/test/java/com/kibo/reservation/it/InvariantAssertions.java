package com.kibo.reservation.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The no-oversell invariant (data-model.md), checked from observable database state only:
 * <pre>
 *   total_quantity - available_quantity == SUM(quantity of ACTIVE + CONFIRMED holds)
 *   0 <= available_quantity <= total_quantity
 * </pre>
 */
public final class InvariantAssertions {

    private InvariantAssertions() {
    }

    public static void assertInventoryInvariant(JdbcTemplate jdbc, long dropId) {
        int total = jdbc.queryForObject("SELECT total_quantity FROM drops WHERE id = ?", Integer.class, dropId);
        int available = jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId);
        int consumed = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM holds WHERE drop_id = ? AND status IN ('ACTIVE', 'CONFIRMED')",
                Integer.class, dropId);
        assertThat(available).as("available quantity of drop %d", dropId).isBetween(0, total);
        assertThat(total - available).as("units taken from drop %d vs units held/confirmed", dropId).isEqualTo(consumed);
    }
}
