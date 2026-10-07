package com.kibo.reservation.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body of POST /api/v1/drops/{dropId}/holds. The upper bound (maxPerHold) depends on the drop. */
public record CreateHoldRequest(@NotNull @Min(1) Integer quantity) {
}
