package com.kibo.reservation.domain.exception;

import java.util.List;
import java.util.Map;

/** A business-level validation failure, such as a quantity above the drop's per-hold maximum (FR-005). */
public class InvalidHoldRequestException extends DomainException {

    private final String field;

    public InvalidHoldRequestException(String field, String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
        this.field = field;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("errors", List.of(Map.of("field", field, "message", getMessage())));
    }
}
