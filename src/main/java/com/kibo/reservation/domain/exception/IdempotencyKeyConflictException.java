package com.kibo.reservation.domain.exception;

/** The customer reused a request key with different details (drop or quantity) (FR-009). */
public class IdempotencyKeyConflictException extends DomainException {

    public IdempotencyKeyConflictException(String requestKey) {
        super(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Request key '" + requestKey + "' was already used for a different hold request");
    }
}
