package com.kibo.reservation.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.exception.HoldNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of GET /api/v1/holds/{holdId} (contracts/openapi.yaml). No infrastructure. */
@WebMvcTest(HoldController.class)
class HoldControllerGetTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private HoldService holdService;

    @Test
    void returns200WithTheHoldSchema() throws Exception {
        Hold hold = Hold.createActive(1L, "alice", "k1", 2, NOW, Duration.ofMinutes(5));
        when(holdService.getHold(hold.getId(), "alice")).thenReturn(hold);

        mvc.perform(get("/api/v1/holds/{id}", hold.getId()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(hold.getId().toString()))
                .andExpect(jsonPath("$.dropId").value(1))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.customerId").doesNotExist());
    }

    @Test
    void anOverdueActiveHoldIsShownAsExpired() throws Exception {
        Hold overdue = Hold.createActive(1L, "alice", "k1", 2, NOW.minus(Duration.ofMinutes(10)), Duration.ofMinutes(5));
        when(holdService.getHold(overdue.getId(), "alice")).thenReturn(overdue);

        mvc.perform(get("/api/v1/holds/{id}", overdue.getId()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void missingCustomerHeaderIs400() throws Exception {
        mvc.perform(get("/api/v1/holds/{id}", java.util.UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(holdService);
    }

    @Test
    void anotherCustomersUnknownAndMalformedHoldsAreAll404() throws Exception {
        java.util.UUID id = java.util.UUID.randomUUID();
        when(holdService.getHold(id, "mallory")).thenThrow(new HoldNotFoundException(id.toString()));

        mvc.perform(get("/api/v1/holds/{id}", id).header(ApiHeaders.CUSTOMER_ID, "mallory"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        mvc.perform(get("/api/v1/holds/not-a-uuid").header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
    }
}
