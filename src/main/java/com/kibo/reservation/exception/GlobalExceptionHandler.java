package com.kibo.reservation.exception;

import com.kibo.reservation.api.ApiHeaders;
import com.kibo.reservation.api.dto.CreateHoldRequest;
import com.kibo.reservation.domain.exception.DomainException;
import com.kibo.reservation.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place that turns failures into RFC 7807 problem responses (Constitution XI, spec FR-029).
 *
 * <p>Every response carries {@code code} (an {@link ErrorCode} where one applies) and {@code timestamp}.
 * Stack traces and internal messages are never returned. Spring MVC's own errors (bad path variable,
 * unknown route, wrong method...) keep Spring's standard handling from {@link ResponseEntityExceptionHandler},
 * enriched with the same members.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /** Expected business failures: status from the code, message and context from the exception. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
        ProblemDetail problem = problem(ex.code(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /**
     * The database (source of truth) is unreachable, or a transient database failure occurred: lock wait
     * timeout, deadlock victim, query timeout, a resource that may work on retry ({@link TransientDataAccessException}
     * covers all of these). The transaction was rolled back, nothing was changed, and the request can be
     * retried safely.
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
            TransientDataAccessException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex, WebRequest request) {
        requestContext(log.atWarn(), request)
                .addKeyValue("code", ErrorCode.SERVICE_UNAVAILABLE.name())
                .addKeyValue("status", HttpStatus.SERVICE_UNAVAILABLE.value())
                .addKeyValue("exception", ex.getClass().getSimpleName())
                .log("Request failed, database unavailable: {}", ex.getMessage());
        ProblemDetail problem = problem(ErrorCode.SERVICE_UNAVAILABLE,
                "The service is temporarily unavailable. Please retry.");
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /**
     * A commit that failed and could not be translated to a {@code DataAccessException}. When the cause is a lost
     * or refused connection, it's a database outage like any other (503, safe to retry, because a commit that did
     * not complete leaves the transaction rolled back by InnoDB); anything else is unexpected (500).
     */
    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<ProblemDetail> handleTransactionSystem(TransactionSystemException ex, WebRequest request) {
        return causedByLostConnection(ex) ? handleDatabaseUnavailable(ex, request) : handleUnexpected(ex, request);
    }

    static boolean causedByLostConnection(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof org.hibernate.exception.JDBCConnectionException) {
                return true;
            }
            // SQLState class 08 = connection exception (SQL standard), e.g. MySQL Connector/J "communications link failure".
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
        }
        return false;
    }

    /** Anything unexpected: logged in full, but only a generic message is returned. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, WebRequest request) {
        requestContext(log.atError(), request)
                .addKeyValue("code", ErrorCode.INTERNAL_ERROR.name())
                .addKeyValue("status", HttpStatus.INTERNAL_SERVER_ERROR.value())
                .setCause(ex)
                .log("Unexpected error");
        ProblemDetail problem = problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /**
     * Adds {@code code}, {@code timestamp} and (for validation failures) {@code errors} to Spring MVC's built-in
     * problem responses, and logs the rejection with its context (FR-031, SC-006).
     *
     * <p>Every Spring MVC error gets a {@code code} from the contract's enum: all client-side request problems
     * (400, unknown route 404, 405, 406, 415, ...) are {@code VALIDATION_ERROR}, server-side ones
     * {@code INTERNAL_ERROR}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            ErrorCode code = statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_ERROR;
            problem.setProperty("timestamp", clock.instant());
            problem.setProperty("code", code.name());
            List<Map<String, String>> fieldErrors = fieldErrorsOf(ex);
            if (!fieldErrors.isEmpty()) {
                problem.setProperty("errors", fieldErrors);
            }
            var event = statusCode.is5xxServerError() ? log.atError() : log.atInfo();
            requestContext(event, request)
                    .addKeyValue("code", code.name())
                    .addKeyValue("status", statusCode.value())
                    .addKeyValue("exception", ex.getClass().getSimpleName());
            Integer quantity = quantityOf(ex);
            if (quantity != null) {
                event.addKeyValue("quantity", quantity);
            }
            event.log("Request rejected");
        }
        return response;
    }

    /** {@code errors[{field, message}]} for bean-validation failures. Never echoes the rejected value. */
    private static List<Map<String, String>> fieldErrorsOf(Exception ex) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (ex instanceof MethodArgumentNotValidException invalid) {
            for (FieldError error : invalid.getBindingResult().getFieldErrors()) {
                errors.add(Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())));
            }
        } else if (ex instanceof HandlerMethodValidationException invalid) {
            invalid.getParameterValidationResults().forEach(result -> {
                String field = result.getMethodParameter().getParameterName() == null
                        ? "request" : result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(error -> errors.add(Map.of(
                        "field", error instanceof FieldError fieldError ? fieldError.getField() : field,
                        "message", String.valueOf(error.getDefaultMessage()))));
            });
        }
        return errors;
    }

    /** The requested quantity, when the failed request carried a readable body (for the rejection log). */
    private static Integer quantityOf(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException invalid
                && invalid.getBindingResult().getTarget() instanceof CreateHoldRequest request) {
            return request.quantity();
        }
        if (ex instanceof HandlerMethodValidationException invalid) {
            for (var result : invalid.getParameterValidationResults()) {
                if (result.getArgument() instanceof CreateHoldRequest request) {
                    return request.quantity();
                }
            }
        }
        return null;
    }

    /**
     * A client that only accepts something we cannot produce (for example {@code Accept: application/xml}) must
     * still get a problem response with a code, not a second failure while writing the error. The error body is
     * always {@code application/problem+json}.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            org.springframework.web.HttpMediaTypeNotAcceptableException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        HttpHeaders problemHeaders = new HttpHeaders();
        problemHeaders.putAll(headers);
        problemHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
        return super.handleHttpMediaTypeNotAcceptable(ex, problemHeaders, status, request);
    }

    private static final Pattern SAFE_ID = Pattern.compile(ApiHeaders.ID_PATTERN);

    /**
     * Adds what identifies the request in a rejection log: method, path, and, where present, the drop (path
     * variable) and the customer (header). The customer reference is logged only if it is a well-formed
     * identifier, so arbitrary header text never reaches the log; no body, key or other header is logged.
     */
    private static <B extends org.slf4j.spi.LoggingEventBuilder> B requestContext(B event, WebRequest request) {
        if (request instanceof ServletWebRequest servlet) {
            HttpServletRequest http = servlet.getRequest();
            event.addKeyValue("method", http.getMethod()).addKeyValue("path", http.getRequestURI());
            String customer = http.getHeader(ApiHeaders.CUSTOMER_ID);
            if (customer != null && SAFE_ID.matcher(customer).matches()) {
                event.addKeyValue("customerId", customer);
            }
            Object variables = http.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (variables instanceof Map<?, ?> map) {
                Object dropId = map.get("dropId");
                if (dropId != null && String.valueOf(dropId).matches("\\d{1,18}")) {
                    event.addKeyValue("dropId", dropId);
                }
                Object holdId = map.get("holdId");
                if (holdId != null && String.valueOf(holdId).matches("[0-9a-fA-F-]{36}")) {
                    event.addKeyValue("holdId", holdId);
                }
            }
        }
        return event;
    }

    private ProblemDetail problem(ErrorCode code, String detail) {
        HttpStatus status = statusOf(code);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", clock.instant());
        return problem;
    }

    /** HTTP semantics for each error code (research.md §9). Exhaustive: a new code will not compile until mapped. */
    static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case DROP_NOT_FOUND, HOLD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DROP_NOT_RELEASED, INSUFFICIENT_INVENTORY, HOLD_EXPIRED, INVALID_STATE_TRANSITION,
                 IDEMPOTENCY_KEY_CONFLICT -> HttpStatus.CONFLICT;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
