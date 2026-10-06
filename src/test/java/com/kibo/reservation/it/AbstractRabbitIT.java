package com.kibo.reservation.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.awaitility.Awaitility;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Base for integration tests that need a REAL RabbitMQ (Testcontainers) with lifecycle-event publishing
 * switched on. One broker is shared by every subclass; each subclass purges the audit queue first.
 *
 * <p>Image: {@code rabbitmq:4} (override with {@code -Dkibo.test.rabbitmq-image=...}).
 */
@TestPropertySource(properties = {"kibo.messaging.enabled=true", "kibo.messaging.audit-consumer-enabled=false"})
public abstract class AbstractRabbitIT extends AbstractMySqlIT {

    static final GenericContainer<?> RABBIT = new GenericContainer<>(
            System.getProperty("kibo.test.rabbitmq-image", "rabbitmq:4"))
            .withEnv("RABBITMQ_DEFAULT_USER", "kibo_it")
            .withEnv("RABBITMQ_DEFAULT_PASS", "kibo_it_pw")
            .withExposedPorts(5672)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void realRabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "kibo_it");
        registry.add("spring.rabbitmq.password", () -> "kibo_it_pw");
    }

    @Autowired
    protected RabbitTemplate rabbit;

    @Autowired
    protected RabbitAdmin admin;

    @Autowired
    protected ObjectMapper json;

    /** Declares the topology (the broker may still be finishing startup) and empties the audit queue. */
    protected void awaitBrokerAndPurge(String auditQueue) {
        Awaitility.await("broker accepts connections and the topology is declared")
                .atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .untilAsserted(() -> {
                    admin.initialize();
                    assertThat(admin.getQueueProperties(auditQueue)).isNotNull();
                });
        admin.purgeQueue(auditQueue, false);
    }

    /** Takes messages off the queue until {@code expected} arrived or the timeout passed. */
    protected List<Message> receive(String queue, int expected, Duration timeout) {
        List<Message> messages = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (messages.size() < expected && System.nanoTime() < deadline) {
            Message message = rabbit.receive(queue, 250);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    protected JsonNode body(Message message) throws Exception {
        return json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
    }
}
