package com.kibo.reservation.it;

import static com.kibo.reservation.it.InvariantAssertions.assertInventoryInvariant;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Regression for FR-009 / FR-022a: customer references and request keys are CASE-SENSITIVE. Under the old
 * case-insensitive column collation, customer "Bob" with key "k1" replayed customer "bob"'s hold and so
 * received its hold id (an ownership leak). These tests pin the fix at the database and at the API.
 */
class CaseSensitivityIT extends AbstractMySqlIT {

    private static final String BASE = "/api/v1";

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
        dropId = drops.save(Drop.create("Open", null, 20, 4, now.minusSeconds(60), now)).getId();
    }

    @Test
    void customersBobAndBobWithTheSameKeyAreIndependentAndNeverSeeEachOthersHold() throws Exception {
        String bobsHold = place("bob", "k1", 2).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String bobsId = JsonPath.read(bobsHold, "$.id");

        // Same key, different case of the customer: a NEW hold for a DIFFERENT customer, not bob's hold.
        String capitalBobHold = place("Bob", "k1", 2).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String capitalBobId = JsonPath.read(capitalBobHold, "$.id");
        assertThat(capitalBobId).isNotEqualTo(bobsId);

        // Each customer's own replay returns their own hold (200, same id), consuming nothing more.
        place("bob", "k1", 2).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(bobsId));
        place("Bob", "k1", 2).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(capitalBobId));

        // Neither can read, confirm or cancel the other's hold: 404, no data.
        read(bobsId, "Bob").andExpect(status().isNotFound());
        read(capitalBobId, "bob").andExpect(status().isNotFound());
        act("confirm", bobsId, "Bob").andExpect(status().isNotFound());
        act("cancel", capitalBobId, "bob").andExpect(status().isNotFound());
        read(bobsId, "bob").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds WHERE drop_id = ?", Integer.class, dropId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM drops WHERE id = ?", Integer.class, dropId))
                .isEqualTo(16);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void keysK1AndK1AreDifferentKeysForTheSameCustomer() throws Exception {
        String first = place("alice", "k1", 1).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String upper = place("alice", "K1", 1).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();

        assertThat((String) JsonPath.read(upper, "$.id")).isNotEqualTo(JsonPath.read(first, "$.id"));
        place("alice", "k1", 1).andExpect(status().isOk()).andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));
        place("alice", "K1", 1).andExpect(status().isOk()).andExpect(jsonPath("$.id").value((String) JsonPath.read(upper, "$.id")));
        // A different quantity under K1 is a conflict for K1 only; k1 is untouched.
        place("alice", "K1", 3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds WHERE drop_id = ?", Integer.class, dropId)).isEqualTo(2);
        assertInventoryInvariant(jdbc, dropId);
    }

    @Test
    void theOwnerCaseMixedWithKeyCaseNeverReplaysAnotherCustomersHold() throws Exception {
        String bobsId = JsonPath.read(place("bob", "k1", 2).andReturn().getResponse().getContentAsString(), "$.id");

        // The exact leak: customer "Bob" + key "k1" used to come back as bob's hold (id and body).
        String body = place("Bob", "k1", 2).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(bobsId);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM holds WHERE id = ?", String.class,
                (String) JsonPath.read(body, "$.id"))).isEqualTo("Bob");
    }

    @Test
    void theDatabaseItselfComparesCustomerAndKeyCaseSensitively() {
        jdbc.update("INSERT INTO holds (id, drop_id, customer_id, request_key, quantity, status, expires_at, created_at, updated_at) "
                + "VALUES (UUID(), ?, 'bob', 'k1', 1, 'ACTIVE', UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", dropId);
        // These would violate uk_holds_customer_request under a case-insensitive collation.
        jdbc.update("INSERT INTO holds (id, drop_id, customer_id, request_key, quantity, status, expires_at, created_at, updated_at) "
                + "VALUES (UUID(), ?, 'Bob', 'k1', 1, 'ACTIVE', UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", dropId);
        jdbc.update("INSERT INTO holds (id, drop_id, customer_id, request_key, quantity, status, expires_at, created_at, updated_at) "
                + "VALUES (UUID(), ?, 'bob', 'K1', 1, 'ACTIVE', UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", dropId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM holds WHERE customer_id = 'bob' AND request_key = 'k1'",
                Integer.class)).isEqualTo(1);
    }

    private ResultActions place(String customer, String key, int quantity) throws Exception {
        return mvc.perform(post(BASE + "/drops/{id}/holds", dropId)
                .header(ApiHeaders.CUSTOMER_ID, customer).header(ApiHeaders.IDEMPOTENCY_KEY, key)
                .contentType("application/json").content("{\"quantity\":" + quantity + "}"));
    }

    private ResultActions read(String holdId, String customer) throws Exception {
        return mvc.perform(get(BASE + "/holds/{id}", holdId).header(ApiHeaders.CUSTOMER_ID, customer));
    }

    private ResultActions act(String action, String holdId, String customer) throws Exception {
        return mvc.perform(post(BASE + "/holds/{id}/" + action, holdId).header(ApiHeaders.CUSTOMER_ID, customer));
    }
}
