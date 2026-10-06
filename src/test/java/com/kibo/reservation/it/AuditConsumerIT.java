package com.kibo.reservation.it;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kibo.reservation.application.HoldService;
import com.kibo.reservation.application.PlaceHoldCommand;
import com.kibo.reservation.domain.Drop;
import com.kibo.reservation.messaging.AuditEventConsumer;
import com.kibo.reservation.repository.DropRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** The demo consumer end to end: hold -> after-commit publish -> exchange -> audit queue -> consumer log line. */
// Dirty the context afterwards: a cached context would keep consuming the shared audit queue from other ITs.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"kibo.messaging.audit-consumer-enabled=true"})
class AuditConsumerIT extends AbstractRabbitIT {

    @Autowired
    private HoldService holds;

    @Autowired
    private DropRepository drops;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger consumerLogger;
    private long dropId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM holds");
        jdbc.update("DELETE FROM drops");
        Instant now = clock.instant();
        dropId = drops.save(Drop.create("Open", null, 5, 4, now.minusSeconds(60), now)).getId();
        awaitBrokerAndPurge("kibo.holds.audit");
        consumerLogger = (Logger) LoggerFactory.getLogger(AuditEventConsumer.class);
        logs.start();
        consumerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        consumerLogger.detachAppender(logs);
    }

    @Test
    void theConsumerReceivesAndLogsEveryEventAndLeavesTheQueueEmpty() {
        UUID id = holds.placeHold(new PlaceHoldCommand(dropId, "alice", "k1", 2)).hold().getId();
        holds.cancel(id, "alice");

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("AUDIT HOLD_CREATED") && m.contains("holdId=" + id) && m.contains("status=ACTIVE"))
                    .anyMatch(m -> m.contains("AUDIT HOLD_CANCELLED") && m.contains("holdId=" + id) && m.contains("status=CANCELLED"));
        });
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var info = admin.getQueueInfo("kibo.holds.audit");
            assertThat(info.getConsumerCount()).as("the demo consumer is attached").isEqualTo(1);
            assertThat(info.getMessageCount()).as("everything consumed").isZero();
        });
    }
}
