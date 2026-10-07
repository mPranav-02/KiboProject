package com.kibo.reservation.domain.exception;

/**
 * Stable, machine-readable failure reasons returned to clients (spec FR-029, contracts/openapi.yaml).
 *
 * <p>Deliberately free of HTTP concepts: the domain does not know about transport. The mapping to
 * HTTP status codes lives in {@code exception.GlobalExceptionHandler}.
 */
public enum ErrorCode {
    VALIDATION_ERROR,
    DROP_NOT_FOUND,
    HOLD_NOT_FOUND,
    DROP_NOT_RELEASED,
    INSUFFICIENT_INVENTORY,
    HOLD_EXPIRED,
    INVALID_STATE_TRANSITION,
    IDEMPOTENCY_KEY_CONFLICT,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
