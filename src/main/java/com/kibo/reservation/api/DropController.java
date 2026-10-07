package com.kibo.reservation.api;

import com.kibo.reservation.api.dto.DropResponse;
import com.kibo.reservation.application.DropQueryService;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Drop read endpoints (US6). HTTP mapping only; no business rules. */
@RestController
@RequestMapping("/api/v1/drops")
public class DropController {

    private final DropQueryService dropQueries;
    private final Clock clock;

    public DropController(DropQueryService dropQueries, Clock clock) {
        this.dropQueries = dropQueries;
        this.clock = clock;
    }

    @GetMapping
    public List<DropResponse> listDrops() {
        Instant now = clock.instant();
        return dropQueries.listDrops().stream().map(drop -> DropResponse.from(drop, now)).toList();
    }

    @GetMapping("/{dropId}")
    public DropResponse getDrop(@PathVariable @Positive long dropId) {
        return DropResponse.from(dropQueries.getDrop(dropId), clock.instant());
    }
}
