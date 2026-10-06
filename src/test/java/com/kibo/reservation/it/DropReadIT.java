package com.kibo.reservation.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** US6 end to end: REST -> service -> MySQL, real schema, real JSON. */
class DropReadIT extends AbstractMySqlIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DropRepository drops;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
    }

    @Test
    void listsDropsOrderedByReleaseTimeWithLiveStatus() throws Exception {
        Instant now = clock.instant();
        Drop upcoming = drops.save(Drop.create("Upcoming", null, 20, 4, now.plus(Duration.ofMinutes(10)), now));
        Drop open = drops.save(Drop.create("Open", "desc", 50, 4, now.minus(Duration.ofMinutes(1)), now));
        Drop soldOut = drops.save(Drop.create("Sold out", null, 1, 1, now.minus(Duration.ofMinutes(2)), now));
        jdbc.update("UPDATE drops SET available_quantity = 0 WHERE id = ?", soldOut.getId());

        mvc.perform(get("/api/v1/drops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(soldOut.getId()))
                .andExpect(jsonPath("$[0].availableQuantity").value(0))
                .andExpect(jsonPath("$[0].availabilityStatus").value("SOLD_OUT"))
                .andExpect(jsonPath("$[1].id").value(open.getId()))
                .andExpect(jsonPath("$[1].availabilityStatus").value("OPEN"))
                .andExpect(jsonPath("$[2].id").value(upcoming.getId()))
                .andExpect(jsonPath("$[2].availabilityStatus").value("UPCOMING"));
    }

    @Test
    void getsOneDropFromTheDatabase() throws Exception {
        Instant now = clock.instant();
        Drop drop = drops.save(Drop.create("Chef's Table", "Five seats", 5, 2, now.minusSeconds(30), now));

        mvc.perform(get("/api/v1/drops/{id}", drop.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Chef's Table"))
                .andExpect(jsonPath("$.description").value("Five seats"))
                .andExpect(jsonPath("$.totalQuantity").value(5))
                .andExpect(jsonPath("$.availableQuantity").value(5))
                .andExpect(jsonPath("$.maxPerHold").value(2))
                .andExpect(jsonPath("$.availabilityStatus").value("OPEN"));
    }

    @Test
    void unknownDropIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/drops/{id}", 987_654L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DROP_NOT_FOUND"));
    }
}
