package com.kibo.reservation.it;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * SC-005 / FR-027: reruns every {@link ConcurrentReservationIT} scenario (200 requests for 50 units, last unit,
 * mixed quantities) with lifecycle events switched ON while both Redis and RabbitMQ are unreachable
 * (closed ports, from {@link AbstractMySqlIT}). Every granted hold now also tries to evict the cache and to
 * publish an event, and both fail; the outcome must be exactly the same: never oversold, invariant intact.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"kibo.messaging.enabled=true", "kibo.messaging.audit-consumer-enabled=true"})
class ConcurrentReservationBrokersDownIT extends ConcurrentReservationIT {
}
