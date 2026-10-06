package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kibo.reservation.api.ApiHeaders;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** US1 end to end over HTTP: REST -> service -> MySQL, verified against the database. */
class HoldCreationIT extends AbstractMySqlIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DropRepository drops;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long openDropId;
    private long upcomingDropId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
        Instant now = clock.instant();
        openDropId = drops.save(Drop.create("Open", null, 5, 4, now.minusSeconds(60), now)).getId();
        upcomingDropId = drops.save(Drop.create("Upcoming", null, 5, 4, now.plusSeconds(600), now)).getId();
    }

    @Test
    void placesAHoldAndPersistsItWithTheDecrement() throws Exception {
        String location = mvc.perform(place(openDropId, "alice", "k1", 2))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andReturn().getResponse().getHeader("Location");

        String holdId = location.substring(location.lastIndexOf('/') + 1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM holds WHERE id = ?", holdId);
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("quantity", 2)
                .containsEntry("customer_id", "alice").containsEntry("request_key", "k1");
        assertThat(available(openDropId)).isEqualTo(3);
        assertInventoryInvariant(jdbc, openDropId);

        // Retry with the same key: 200, same hold, no further decrement.
        mvc.perform(place(openDropId, "alice", "k1", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(holdId));
        assertThat(available(openDropId)).isEqualTo(3);
    }

    @Test
    void insufficientInventoryIs409AndChangesNothing() throws Exception {
        mvc.perform(place(openDropId, "alice", "k1", 4)).andExpect(status().isCreated());

        mvc.perform(place(openDropId, "bob", "k1", 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"))
                .andExpect(jsonPath("$.availableQuantity").value(1));

        assertThat(available(openDropId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds WHERE customer_id = 'bob'", Integer.class)).isZero();
        assertInventoryInvariant(jdbc, openDropId);
    }

    @Test
    void rejectionsLeaveInventoryUntouched() throws Exception {
        mvc.perform(place(upcomingDropId, "alice", "k1", 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DROP_NOT_RELEASED"));
        mvc.perform(place(987_654L, "alice", "k2", 1))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DROP_NOT_FOUND"));
        mvc.perform(place(openDropId, "alice", "k3", 5))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(available(openDropId)).isEqualTo(5);
        assertThat(available(upcomingDropId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds", Integer.class)).isZero();
    }

    private int available(long dropId) {
        return jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId);
    }

    private static MockHttpServletRequestBuilder place(long dropId, String customer, String key, int quantity) {
        return post("/api/v1/drops/{dropId}/holds", dropId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiHeaders.CUSTOMER_ID, customer)
                .header(ApiHeaders.IDEMPOTENCY_KEY, key)
                .content("{\"quantity\":" + quantity + "}");
    }
}
