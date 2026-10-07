package com.kibo.reservation.api;

import com.kibo.reservation.api.dto.CreateHoldRequest;
import com.kibo.reservation.api.dto.HoldResponse;
import com.kibo.reservation.application.HoldPlacement;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import com.kibo.reservation.domain.exception.HoldNotFoundException;
import com.kibo.reservation.domain.exception.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hold endpoints. HTTP mapping and input validation only; all rules live in {@link HoldService}. */
@RestController
@RequestMapping("/api/v1")
public class HoldController {

    private static final Logger log = LoggerFactory.getLogger(HoldController.class);

    private final HoldService holdService;
    private final Clock clock;

    public HoldController(HoldService holdService, Clock clock) {
        this.holdService = holdService;
        this.clock = clock;
    }

    /** 201 + Location for a new hold; 200 with the original hold for an idempotent replay. */
    @PostMapping("/drops/{dropId}/holds")
    public ResponseEntity<HoldResponse> placeHold(
            @PathVariable @Positive long dropId,
            @RequestHeader(ApiHeaders.CUSTOMER_ID) @Pattern(regexp = ApiHeaders.ID_PATTERN) String customerId,
            @RequestHeader(ApiHeaders.IDEMPOTENCY_KEY) @Pattern(regexp = ApiHeaders.ID_PATTERN) String requestKey,
            @RequestBody @Valid CreateHoldRequest request) {
        HoldPlacement placement = holdService.placeHold(
                new PlaceHoldCommand(dropId, customerId, requestKey, request.quantity()));
        HoldResponse body = HoldResponse.from(placement.hold(), clock.instant());
        return placement.created()
                ? ResponseEntity.created(URI.create("/api/v1/holds/" + body.id())).body(body)
                : ResponseEntity.ok(body);
    }

    /**
     * The customer's own hold. An ACTIVE hold past its expiry is reported as EXPIRED. 404 for an unknown,
     * malformed or someone else's hold, which is also where the {@code Location} of a new hold points.
     */
    @GetMapping("/holds/{holdId}")
    public HoldResponse get(
            @PathVariable String holdId,
            @RequestHeader(ApiHeaders.CUSTOMER_ID) @Pattern(regexp = ApiHeaders.ID_PATTERN) String customerId) {
        return HoldResponse.from(holdService.getHold(parseHoldId(holdId, customerId, "get"), customerId), clock.instant());
    }

    /** 200 with the CONFIRMED hold, also for a repeated confirm. */
    @PostMapping("/holds/{holdId}/confirm")
    public HoldResponse confirm(
            @PathVariable String holdId,
            @RequestHeader(ApiHeaders.CUSTOMER_ID) @Pattern(regexp = ApiHeaders.ID_PATTERN) String customerId) {
        return HoldResponse.from(holdService.confirm(parseHoldId(holdId, customerId, "confirm"), customerId), clock.instant());
    }

    /** 200 with the CANCELLED hold, also for a repeated cancel. */
    @PostMapping("/holds/{holdId}/cancel")
    public HoldResponse cancel(
            @PathVariable String holdId,
            @RequestHeader(ApiHeaders.CUSTOMER_ID) @Pattern(regexp = ApiHeaders.ID_PATTERN) String customerId) {
        return HoldResponse.from(holdService.cancel(parseHoldId(holdId, customerId, "cancel"), customerId), clock.instant());
    }

    /** A malformed id is the same 404 as an unknown id: no existence oracle (data-model.md). */
    private static UUID parseHoldId(String holdId, String customerId, String operation) {
        try {
            return UUID.fromString(holdId);
        } catch (IllegalArgumentException malformed) {
            // Logged with the caller and the reason, not the text (it is arbitrary client input).
            log.atInfo()
                    .addKeyValue("customerId", customerId)
                    .addKeyValue("operation", operation)
                    .addKeyValue("code", ErrorCode.HOLD_NOT_FOUND.name())
                    .addKeyValue("reason", "malformed holdId")
                    .addKeyValue("holdIdLength", holdId.length())
                    .log("Hold request rejected");
            throw new HoldNotFoundException(holdId);
        }
    }
}
