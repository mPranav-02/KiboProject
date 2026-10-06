package com.kibo.reservation.exception;

import com.kibo.reservation.domain.exception.DomainException;
import com.kibo.reservation.domain.exception.ErrorCode;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
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
     * The database (source of truth) is unreachable, or a lock could not be acquired in time (lock wait
     * timeout / deadlock victim). The transaction was rolled back, nothing was changed, and the request can
     * be retried safely.
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
            PessimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
        log.warn("Database unavailable: {}", ex.getMessage());
        ProblemDetail problem = problem(ErrorCode.SERVICE_UNAVAILABLE,
                "The service is temporarily unavailable. Please retry.");
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** Anything unexpected: logged in full, but only a generic message is returned. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail problem = problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** Adds {@code code} and {@code timestamp} to Spring MVC's built-in problem responses. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("timestamp", clock.instant());
            if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
                problem.setProperty("code", ErrorCode.VALIDATION_ERROR.name());
            } else if (statusCode.is5xxServerError()) {
                problem.setProperty("code", ErrorCode.INTERNAL_ERROR.name());
            }
        }
        return response;
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
