package com.kibo.reservation.domain.exception;

public class DropNotFoundException extends DomainException {

    public DropNotFoundException(long dropId) {
        super(ErrorCode.DROP_NOT_FOUND, "Drop " + dropId + " was not found");
    }
}
