package com.kibo.reservation.it;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * SC-005 / FR-027: reruns every {@link HoldRaceIT} race (confirm vs expire, cancel vs expire, all three at once)
 * with lifecycle events switched ON while both Redis and RabbitMQ are unreachable (closed ports, from
 * {@link AbstractMySqlIT}). Each winning transition's after-commit cache eviction and event publish fail; there
 * must still be exactly one winner per hold and units must still come back exactly once.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"kibo.messaging.enabled=true", "kibo.messaging.audit-consumer-enabled=true"})
class HoldRaceBrokersDownIT extends HoldRaceIT {
}
