package com.kibo.reservation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kibo.reservation.application.HoldPlacement;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Hold;
import com.kibo.reservation.domain.exception.DropNotFoundException;
import com.kibo.reservation.domain.exception.DropNotReleasedException;
import com.kibo.reservation.domain.exception.IdempotencyKeyConflictException;
import com.kibo.reservation.domain.exception.InsufficientInventoryException;
import com.kibo.reservation.domain.exception.InvalidHoldRequestException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** HTTP contract of POST /api/v1/drops/{dropId}/holds (contracts/openapi.yaml). No infrastructure. */
@WebMvcTest(HoldController.class)
class HoldControllerCreateTest {

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
    void newHoldIs201WithLocationAndBody() throws Exception {
        when(holdService.placeHold(new PlaceHoldCommand(1L, "alice", "k1", 2))).thenReturn(new HoldPlacement(hold, true));

        mvc.perform(place(1, "alice", "k1", "{\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/holds/" + hold.getId()))
                .andExpect(jsonPath("$.id").value(hold.getId().toString()))
                .andExpect(jsonPath("$.dropId").value(1))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-06T10:00:00Z"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-06T10:05:00Z"))
                .andExpect(jsonPath("$.resolvedAt").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.requestKey").doesNotExist());
    }

    @Test
    void idempotentReplayIs200WithTheOriginalHold() throws Exception {
        when(holdService.placeHold(any())).thenReturn(new HoldPlacement(hold, false));

        mvc.perform(place(1, "alice", "k1", "{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(hold.getId().toString()));
    }

    @Test
    void missingCustomerHeaderIsAValidationError() throws Exception {
        mvc.perform(post("/api/v1/drops/1/holds").contentType(MediaType.APPLICATION_JSON)
                        .header(ApiHeaders.IDEMPOTENCY_KEY, "k1").content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(holdService, never()).placeHold(any());
    }

    @Test
    void missingIdempotencyKeyIsAValidationError() throws Exception {
        mvc.perform(post("/api/v1/drops/1/holds").contentType(MediaType.APPLICATION_JSON)
                        .header(ApiHeaders.CUSTOMER_ID, "alice").content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(holdService, never()).placeHold(any());
    }

    @Test
    void malformedHeadersAreValidationErrors() throws Exception {
        mvc.perform(place(1, "alice smith", "k1", "{\"quantity\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(place(1, "alice", "k".repeat(65), "{\"quantity\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(holdService, never()).placeHold(any());
    }

    @Test
    void invalidBodiesAreValidationErrors() throws Exception {
        for (String body : new String[] {"{}", "{\"quantity\":0}", "{\"quantity\":-1}", "{\"quantity\":\"two\"}", "not json"}) {
            mvc.perform(place(1, "alice", "k1", body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verify(holdService, never()).placeHold(any());
    }

    @Test
    void quantityAboveMaxPerHoldIsA400WithFieldErrors() throws Exception {
        when(holdService.placeHold(any())).thenThrow(new InvalidHoldRequestException("quantity", "quantity must be between 1 and 4"));

        mvc.perform(place(1, "alice", "k1", "{\"quantity\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
    }

    @Test
    void unknownDropIs404() throws Exception {
        when(holdService.placeHold(any())).thenThrow(new DropNotFoundException(1L));
        mvc.perform(place(1, "alice", "k1", "{\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DROP_NOT_FOUND"));
    }

    @Test
    void insufficientInventoryIs409WithAvailableQuantity() throws Exception {
        when(holdService.placeHold(any())).thenThrow(new InsufficientInventoryException(1L, 3, 2));
        mvc.perform(place(1, "alice", "k1", "{\"quantity\":3}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"))
                .andExpect(jsonPath("$.availableQuantity").value(2));
    }

    @Test
    void notReleasedIs409() throws Exception {
        when(holdService.placeHold(any())).thenThrow(new DropNotReleasedException(1L, NOW.plusSeconds(60)));
        mvc.perform(place(1, "alice", "k1", "{\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DROP_NOT_RELEASED"))
                .andExpect(jsonPath("$.startsAt").value("2026-10-06T10:01:00Z"));
    }

    @Test
    void reusedKeyWithDifferentDetailsIs409() throws Exception {
        when(holdService.placeHold(any())).thenThrow(new IdempotencyKeyConflictException("k1"));
        mvc.perform(place(1, "alice", "k1", "{\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    private static MockHttpServletRequestBuilder place(long dropId, String customer, String key, String body) {
        return post("/api/v1/drops/{dropId}/holds", dropId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiHeaders.CUSTOMER_ID, customer)
                .header(ApiHeaders.IDEMPOTENCY_KEY, key)
                .content(body);
    }
}
