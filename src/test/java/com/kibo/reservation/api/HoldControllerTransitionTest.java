package com.kibo.reservation.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.HoldStatus;
import com.kibo.reservation.domain.exception.HoldExpiredException;
import com.kibo.reservation.domain.exception.HoldNotFoundException;
import com.kibo.reservation.domain.exception.InvalidStateTransitionException;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of POST /api/v1/holds/{holdId}/confirm and /cancel (contracts/openapi.yaml). No infrastructure. */
@WebMvcTest(HoldController.class)
class HoldControllerTransitionTest {

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

    private final Hold hold = Hold.createActive(1L, "alice", "k1", 2, NOW, Duration.ofMinutes(5));

    @Test
    void confirmReturns200WithTheConfirmedHold() throws Exception {
        ReflectionTestUtils.setField(hold, "status", HoldStatus.CONFIRMED);
        when(holdService.confirm(hold.getId(), "alice")).thenReturn(hold);

        mvc.perform(post("/api/v1/holds/{id}/confirm", hold.getId()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(hold.getId().toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.quantity").value(2));
    }

    @Test
    void cancelReturns200WithTheCancelledHold() throws Exception {
        ReflectionTestUtils.setField(hold, "status", HoldStatus.CANCELLED);
        when(holdService.cancel(hold.getId(), "alice")).thenReturn(hold);

        mvc.perform(post("/api/v1/holds/{id}/cancel", hold.getId()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void unknownOrForeignHoldIs404HoldNotFound() throws Exception {
        when(holdService.confirm(hold.getId(), "mallory")).thenThrow(new HoldNotFoundException(hold.getId().toString()));
        when(holdService.cancel(hold.getId(), "mallory")).thenThrow(new HoldNotFoundException(hold.getId().toString()));

        for (String action : new String[] {"confirm", "cancel"}) {
            mvc.perform(post("/api/v1/holds/{id}/" + action, hold.getId()).header(ApiHeaders.CUSTOMER_ID, "mallory"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        }
    }

    @Test
    void malformedHoldIdIs404WithoutCallingTheService() throws Exception {
        for (String action : new String[] {"confirm", "cancel"}) {
            mvc.perform(post("/api/v1/holds/not-a-uuid/" + action).header(ApiHeaders.CUSTOMER_ID, "alice"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        }
        verifyNoInteractions(holdService);
    }

    @Test
    void expiredHoldIs409HoldExpired() throws Exception {
        when(holdService.confirm(hold.getId(), "alice")).thenThrow(new HoldExpiredException(hold.getId(), hold.getExpiresAt()));
        when(holdService.cancel(hold.getId(), "alice")).thenThrow(new HoldExpiredException(hold.getId(), hold.getExpiresAt()));

        for (String action : new String[] {"confirm", "cancel"}) {
            mvc.perform(post("/api/v1/holds/{id}/" + action, hold.getId()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"))
                    .andExpect(jsonPath("$.currentStatus").value("EXPIRED"));
        }
    }

    @Test
    void invalidTransitionIs409WithTheCurrentStatus() throws Exception {
        when(holdService.confirm(hold.getId(), "alice")).thenThrow(
                new InvalidStateTransitionException(hold.getId(), HoldStatus.CANCELLED, HoldStatus.CONFIRMED));
        when(holdService.cancel(hold.getId(), "alice")).thenThrow(
                new InvalidStateTransitionException(hold.getId(), HoldStatus.CONFIRMED, HoldStatus.CANCELLED));

        mvc.perform(post("/api/v1/holds/{id}/confirm", hold.getId()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
        mvc.perform(post("/api/v1/holds/{id}/cancel", hold.getId()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("CONFIRMED"));
    }

    @Test
    void missingOrInvalidCustomerHeaderIs400WithoutCallingTheService() throws Exception {
        for (String action : new String[] {"confirm", "cancel"}) {
            mvc.perform(post("/api/v1/holds/{id}/" + action, hold.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            mvc.perform(post("/api/v1/holds/{id}/" + action, hold.getId()).header(ApiHeaders.CUSTOMER_ID, "bad id!"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verify(holdService, never()).confirm(hold.getId(), "bad id!");
        verifyNoInteractions(holdService);
    }
}
