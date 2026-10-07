package com.kibo.reservation.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.kibo.reservation.api.ApiHeaders;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** US5 against real MySQL: GET /api/v1/holds/{holdId}, and the Location header of hold creation. */
class HoldReadIT extends AbstractMySqlIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DropRepository drops;

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
        dropId = drops.save(Drop.create("Open", null, 10, 4, now.minusSeconds(60), now)).getId();
    }

    @Test
    void theLocationHeaderOfAnewHoldPointsToAWorkingEndpointForTheOwner() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/drops/{id}/holds", dropId)
                        .header(ApiHeaders.CUSTOMER_ID, "alice").header(ApiHeaders.IDEMPOTENCY_KEY, "k1")
                        .contentType("application/json").content("{\"quantity\":2}"))
                .andExpect(status().isCreated()).andReturn();
        String location = created.getResponse().getHeader("Location");
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertThat(location).isEqualTo("/api/v1/holds/" + id);

        mvc.perform(get(location).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.dropId").value(dropId))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void anotherCustomerAnUnknownIdAndAMalformedIdAreAllTheSame404() throws Exception {
        String id = placeHoldAs("alice", "k1");

        for (String other : new String[] {"bob", "Alice", "ALICE"}) {
            mvc.perform(get("/api/v1/holds/{id}", id).header(ApiHeaders.CUSTOMER_ID, other))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        }
        mvc.perform(get("/api/v1/holds/{id}", java.util.UUID.randomUUID()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        mvc.perform(get("/api/v1/holds/not-a-uuid").header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        mvc.perform(get("/api/v1/holds/{id}", id)).andExpect(status().isBadRequest());
    }

    @Test
    void anOverdueActiveHoldReadsAsExpiredWithoutTheReadChangingAnything() throws Exception {
        String id = placeHoldAs("alice", "k1");
        jdbc.update("UPDATE holds SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id);

        mvc.perform(get("/api/v1/holds/{id}", id).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));

        assertThat(jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, id))
                .as("a read never settles the hold").isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId))
                .as("and never returns units").isEqualTo(8);
    }

    @Test
    void theReadReflectsConfirmAndCancel() throws Exception {
        String confirmed = placeHoldAs("alice", "k1");
        String cancelled = placeHoldAs("alice", "k2");
        mvc.perform(post("/api/v1/holds/{id}/confirm", confirmed).header(ApiHeaders.CUSTOMER_ID, "alice")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/holds/{id}/cancel", cancelled).header(ApiHeaders.CUSTOMER_ID, "alice")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/holds/{id}", confirmed).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(get("/api/v1/holds/{id}", cancelled).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.resolvedAt").exists());
    }

    private String placeHoldAs(String customer, String key) throws Exception {
        String body = mvc.perform(post("/api/v1/drops/{id}/holds", dropId)
                        .header(ApiHeaders.CUSTOMER_ID, customer).header(ApiHeaders.IDEMPOTENCY_KEY, key)
                        .contentType("application/json").content("{\"quantity\":2}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
