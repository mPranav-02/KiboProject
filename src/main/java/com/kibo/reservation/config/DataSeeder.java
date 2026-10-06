package com.kibo.reservation.config;

import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a few demo drops on startup (plan §11) so the API is usable immediately after
 * {@code docker compose up}. Runs only when {@code kibo.seed.enabled=true} AND the drops table is empty,
 * so restarts never duplicate or reset data. Release times are relative to "now".
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final DropRepository drops;
    private final KiboProperties properties;
    private final Clock clock;

    public DataSeeder(DropRepository drops, KiboProperties properties, Clock clock) {
        this.drops = drops;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.seed().enabled()) {
            log.info("Seed data disabled (kibo.seed.enabled=false)");
            return;
        }
        if (drops.count() > 0) {
            log.info("Drops already present; skipping seed data");
            return;
        }
        List<Drop> seeded = drops.saveAll(seedDrops(clock.instant(), properties.hold().defaultMaxPerHold()));
        log.info("Seeded {} drops", seeded.size());
    }

    static List<Drop> seedDrops(Instant now, int maxPerHold) {
        Instant released = now.minus(Duration.ofMinutes(1));
        return List.of(
                Drop.create("Limited Sneaker Release",
                        "Hand-numbered run of the KIBO One sneaker.", 50, maxPerHold, released, now),
                Drop.create("Chef's Table - Friday 8pm",
                        "Five seats at the chef's counter.", 5, maxPerHold, released, now),
                Drop.create("Front-Row Concert Ticket",
                        "A single front-row seat: the last-unit race.", 1, maxPerHold, released, now),
                Drop.create("Dermatology Appointment Slots",
                        "Same-week appointment slots, released in 10 minutes.", 20, maxPerHold,
                        now.plus(Duration.ofMinutes(10)), now));
    }
}
