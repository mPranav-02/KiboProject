package com.kibo.reservation.it;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base class for integration tests against a real MySQL 8.4 (Constitution XIII: database-level
 * guarantees are proven against MySQL, never H2). One container is shared by all IT classes.
 *
 * <p>Uses a mock servlet environment (no port) so subclasses can call the REST API through MockMvc.
 *
 * <p>Profile "test": no seed data, background expiry sweep effectively disabled (tests drive expiry
 * explicitly, so race tests stay deterministic).
 *
 * <p>Redis and RabbitMQ default to a closed local port. That resolves the required ${...} placeholders,
 * means nothing real is ever contacted (both clients connect lazily), and, because Redis really is
 * unreachable, every test that does not care about it also proves the service works without it
 * (FR-027, SC-005). These static test properties rank below {@code @DynamicPropertySource}, so a test that
 * needs a real Redis just registers its own host and port (see {@code DropCacheIT}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=1",
        "spring.data.redis.password=",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=1",
        "spring.rabbitmq.username=unused",
        "spring.rabbitmq.password=unused"})
public abstract class AbstractMySqlIT {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static {
        MYSQL.start();
    }
}
