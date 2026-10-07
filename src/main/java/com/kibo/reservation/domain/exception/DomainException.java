package com.kibo.reservation.domain.exception;

import java.util.Map;
import java.util.Objects;

/**
 * Base class for expected business failures. Each carries a stable {@link ErrorCode} and optional
 * context properties (for example {@code currentStatus} or {@code availableQuantity}) that are added
 * to the problem response.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode code;

    protected DomainException(ErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }

    /** Extra problem-response members. Empty by default. */
    public Map<String, Object> properties() {
        return Map.of();
    }
}
