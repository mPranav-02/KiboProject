package com.kibo.reservation.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionSystemException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kibo.reservation.api.ApiHeaders;
import com.kibo.reservation.api.HoldController;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.domain.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every failure is an RFC 7807 problem with {@code code} and {@code timestamp} (SC-006, FR-029), never a stack
 * trace, and every rejection is logged with its code and context (FR-031). No infrastructure.
 */
@WebMvcTest(HoldController.class)
class GlobalExceptionHandlerTest {

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

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger handlerLogger;

    @BeforeEach
    void captureLogs() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        handlerLogger.detachAppender(logs);
    }

    @Test
    void everyErrorCodeMapsToAnHttpStatus() {
        Map<ErrorCode, HttpStatus> expected = new HashMap<>();
        expected.put(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST);
        expected.put(ErrorCode.DROP_NOT_FOUND, HttpStatus.NOT_FOUND);
        expected.put(ErrorCode.HOLD_NOT_FOUND, HttpStatus.NOT_FOUND);
        for (ErrorCode code : new ErrorCode[] {ErrorCode.DROP_NOT_RELEASED, ErrorCode.INSUFFICIENT_INVENTORY,
                ErrorCode.HOLD_EXPIRED, ErrorCode.INVALID_STATE_TRANSITION, ErrorCode.IDEMPOTENCY_KEY_CONFLICT}) {
            expected.put(code, HttpStatus.CONFLICT);
        }
        expected.put(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
        expected.put(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(GlobalExceptionHandler.statusOf(code)).as(code.name()).isEqualTo(expected.get(code));
        }
    }

    @Test
    void invalidBodyIs400WithFieldErrorsAndIsLoggedWithTheRequestContext() throws Exception {
        mvc.perform(post("/api/v1/drops/{id}/holds", 7)
                        .header(ApiHeaders.CUSTOMER_ID, "alice").header(ApiHeaders.IDEMPOTENCY_KEY, "secret-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        ILoggingEvent entry = singleRejection();
        assertThat(entry.getLevel()).isEqualTo(Level.INFO);
        assertThat(keyValues(entry)).containsEntry("code", "VALIDATION_ERROR").containsEntry("status", "400")
                .containsEntry("customerId", "alice").containsEntry("dropId", "7").containsEntry("quantity", "0")
                .containsEntry("method", "POST").containsKey("path")
                .doesNotContainKey("requestKey").doesNotContainValue("secret-key-1");
    }

    @Test
    void aMissingOrInvalidCustomerHeaderIs400WithErrorsAndNeverLogsGarbage() throws Exception {
        mvc.perform(get("/api/v1/holds/{id}", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        logs.list.clear();
        mvc.perform(get("/api/v1/holds/{id}", UUID.randomUUID()).header(ApiHeaders.CUSTOMER_ID, "bad id <script>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        assertThat(keyValues(singleRejection())).doesNotContainKey("customerId")
                .containsEntry("code", "VALIDATION_ERROR");
    }

    @Test
    void springMvcErrorsAllCarryACodeAndATimestamp() throws Exception {
        // the handler must run (and succeed) before the response is negotiated, so the 406 comes from writing
        when(holdService.getHold(any(), any())).thenReturn(
                com.kibo.reservation.domain.Hold.createActive(1L, "alice", "k1", 1,
                        java.time.Instant.parse("2026-10-06T10:00:00Z"), java.time.Duration.ofMinutes(5)));
        mvc.perform(get("/api/v1/nope")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.timestamp").exists());
        mvc.perform(delete("/api/v1/holds/{id}", UUID.randomUUID()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.timestamp").exists());
        mvc.perform(post("/api/v1/drops/7/holds")
                        .header(ApiHeaders.CUSTOMER_ID, "alice").header(ApiHeaders.IDEMPOTENCY_KEY, "k1")
                        .contentType(MediaType.TEXT_PLAIN).content("quantity=1"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.timestamp").exists());
        mvc.perform(get("/api/v1/holds/{id}", UUID.randomUUID()).header(ApiHeaders.CUSTOMER_ID, "alice")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.timestamp").exists());
        mvc.perform(post("/api/v1/drops/7/holds")
                        .header(ApiHeaders.CUSTOMER_ID, "alice").header(ApiHeaders.IDEMPOTENCY_KEY, "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/drops/abc/holds")
                        .header(ApiHeaders.CUSTOMER_ID, "alice").header(ApiHeaders.IDEMPOTENCY_KEY, "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void aDatabaseOutageIs503WithACodeAndIsLoggedAsAWarningWithContext() throws Exception {
        UUID id = UUID.randomUUID();
        when(holdService.getHold(any(), any())).thenThrow(new DataAccessResourceFailureException("db down"));

        mvc.perform(get("/api/v1/holds/{id}", id).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.timestamp").exists());

        ILoggingEvent entry = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow();
        assertThat(keyValues(entry)).containsEntry("code", "SERVICE_UNAVAILABLE").containsEntry("status", "503")
                .containsEntry("customerId", "alice").containsEntry("holdId", id.toString())
                .containsEntry("exception", "DataAccessResourceFailureException");
    }

    @ParameterizedTest
    @MethodSource("transientDatabaseFailures")
    void transientDatabaseFailuresAre503SoTheClientCanRetry(RuntimeException failure) throws Exception {
        when(holdService.getHold(any(), any())).thenThrow(failure);

        mvc.perform(get("/api/v1/holds/{id}", UUID.randomUUID()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    static Stream<RuntimeException> transientDatabaseFailures() {
        return Stream.of(
                new CannotAcquireLockException("deadlock victim"),
                new QueryTimeoutException("query timed out"),
                new TransientDataAccessResourceException("resource busy"),
                // A commit whose connection dropped (Connector/J reports SQLState 08S01).
                new TransactionSystemException("Could not commit JPA transaction",
                        new SQLException("Communications link failure", "08S01")),
                new TransactionSystemException("Could not commit JPA transaction",
                        new SQLNonTransientConnectionException("connection closed")));
    }

    @Test
    void aCommitFailureNotCausedByTheConnectionIsStill500() throws Exception {
        when(holdService.getHold(any(), any())).thenThrow(
                new TransactionSystemException("Could not commit", new IllegalStateException("bug")));

        mvc.perform(get("/api/v1/holds/{id}", UUID.randomUUID()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void anUnexpectedFailureIs500WithAGenericMessageAndNoStackTrace() throws Exception {
        when(holdService.getHold(any(), any())).thenThrow(new IllegalStateException("internal detail com.kibo.Secret"));

        mvc.perform(get("/api/v1/holds/{id}", UUID.randomUUID()).header(ApiHeaders.CUSTOMER_ID, "alice"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Secret"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("at com.kibo"))));

        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(keyValues(e)).containsEntry("code", "INTERNAL_ERROR").containsEntry("customerId", "alice");
            assertThat(e.getThrowableProxy()).isNotNull();
        });
    }

    private ILoggingEvent singleRejection() {
        return logs.list.stream().filter(e -> "Request rejected".equals(e.getFormattedMessage()))
                .reduce((first, second) -> second).orElseThrow(() -> new AssertionError("no rejection logged: " + logs.list));
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        Map<String, Object> values = new HashMap<>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(pair -> values.put(pair.key, String.valueOf(pair.value)));
        }
        return values;
    }
}
