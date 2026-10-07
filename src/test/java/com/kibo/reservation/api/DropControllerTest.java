package com.kibo.reservation.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kibo.reservation.application.DropQueryService;
import com.kibo.reservation.application.DropSnapshot;
import com.kibo.reservation.domain.exception.DropNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of the drop read endpoints (contracts/openapi.yaml). No database, no infrastructure. */
@WebMvcTest(DropController.class)
class DropControllerTest {

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
    private DropQueryService dropQueries;

    private static final DropSnapshot OPEN = new DropSnapshot(1L, "Open", "d", 50, 12, 4, NOW.minusSeconds(60));
    private static final DropSnapshot SOLD_OUT = new DropSnapshot(2L, "Sold out", null, 1, 0, 4, NOW.minusSeconds(60));
    private static final DropSnapshot UPCOMING = new DropSnapshot(3L, "Upcoming", "d", 20, 20, 4, NOW.plusSeconds(600));

    @Test
    void listReturnsAllDropsWithAvailabilityStatusComputedFromTheClock() throws Exception {
        when(dropQueries.listDrops()).thenReturn(List.of(OPEN, SOLD_OUT, UPCOMING));

        mvc.perform(get("/api/v1/drops"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Open"))
                .andExpect(jsonPath("$[0].totalQuantity").value(50))
                .andExpect(jsonPath("$[0].availableQuantity").value(12))
                .andExpect(jsonPath("$[0].maxPerHold").value(4))
                .andExpect(jsonPath("$[0].startsAt").value("2026-10-06T09:59:00Z"))
                .andExpect(jsonPath("$[0].availabilityStatus").value("OPEN"))
                .andExpect(jsonPath("$[1].availabilityStatus").value("SOLD_OUT"))
                .andExpect(jsonPath("$[2].availabilityStatus").value("UPCOMING"));
    }

    @Test
    void listIsAnEmptyArrayWhenThereAreNoDrops() throws Exception {
        when(dropQueries.listDrops()).thenReturn(List.of());
        mvc.perform(get("/api/v1/drops")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void getReturnsOneDrop() throws Exception {
        when(dropQueries.getDrop(1L)).thenReturn(OPEN);

        mvc.perform(get("/api/v1/drops/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.description").value("d"))
                .andExpect(jsonPath("$.availabilityStatus").value("OPEN"));
    }

    @Test
    void unknownDropIsA404ProblemWithDropNotFoundCode() throws Exception {
        when(dropQueries.getDrop(99L)).thenThrow(new DropNotFoundException(99L));

        mvc.perform(get("/api/v1/drops/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("DROP_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("99")))
                .andExpect(jsonPath("$.timestamp").value("2026-10-06T10:00:00Z"))
                .andExpect(jsonPath("$.instance").value("/api/v1/drops/99"));
    }

    @Test
    void nonNumericDropIdIsAValidationError() throws Exception {
        mvc.perform(get("/api/v1/drops/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void nonPositiveDropIdIsAValidationError() throws Exception {
        mvc.perform(get("/api/v1/drops/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/drops/-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void databaseUnavailableIsA503WithoutInternalDetails() throws Exception {
        when(dropQueries.listDrops()).thenThrow(new CannotGetJdbcConnectionException("Communications link failure to db-host:3306"));

        mvc.perform(get("/api/v1/drops"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("db-host"))));
    }

    @Test
    void unexpectedErrorsAre500WithAGenericMessageAndNoStackTrace() throws Exception {
        when(dropQueries.getDrop(1L)).thenThrow(new IllegalStateException("secret internal detail"));

        mvc.perform(get("/api/v1/drops/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(Matchers.not(Matchers.containsString("secret internal detail"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("stackTrace"))));
    }
}
