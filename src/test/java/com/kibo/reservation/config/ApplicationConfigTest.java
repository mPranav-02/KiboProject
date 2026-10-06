package com.kibo.reservation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards application.yml against hardcoded infrastructure (Constitution XIV) and pins the
 * settings the design relies on. Reads the raw YAML, so no Spring context or infrastructure.
 */
class ApplicationConfigTest {

    private static PropertySource<?> yaml;

    @BeforeAll
    static void loadYaml() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        assertThat(sources).hasSize(1);
        yaml = sources.get(0);
    }

    @ParameterizedTest
    @CsvSource({
        "spring.datasource.url,        DB_URL",
        "spring.datasource.username,   DB_USERNAME",
        "spring.datasource.password,   DB_PASSWORD",
        "spring.data.redis.host,       REDIS_HOST",
        "spring.data.redis.port,       REDIS_PORT",
        "spring.data.redis.password,   REDIS_PASSWORD",
        "spring.rabbitmq.host,         RABBITMQ_HOST",
        "spring.rabbitmq.port,         RABBITMQ_PORT",
        "spring.rabbitmq.username,     RABBITMQ_USERNAME",
        "spring.rabbitmq.password,     RABBITMQ_PASSWORD"
    })
    void infrastructureSettingsComeOnlyFromEnvironmentWithoutDefaults(String key, String envVar) {
        assertThat(value(key)).isEqualTo("${" + envVar + "}");
    }

    @Test
    void persistenceSettingsMatchTheConcurrencyDesign() {
        assertThat(value("spring.datasource.hikari.transaction-isolation")).isEqualTo("TRANSACTION_READ_COMMITTED");
        assertThat(value("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(value("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
        assertThat(value("spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type")).isEqualTo("TIMESTAMP");
        assertThat(value("spring.jpa.open-in-view")).isEqualTo("false");
    }

    @Test
    void businessDefaultsArePresent() {
        assertThat(Map.of(
                        "kibo.hold.duration", value("kibo.hold.duration"),
                        "kibo.hold.default-max-per-hold", value("kibo.hold.default-max-per-hold"),
                        "kibo.expiration.interval", value("kibo.expiration.interval"),
                        "kibo.expiration.batch-size", value("kibo.expiration.batch-size"),
                        "kibo.cache.drop-ttl", value("kibo.cache.drop-ttl"),
                        "kibo.seed.enabled", value("kibo.seed.enabled")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "kibo.hold.duration", "PT5M",
                        "kibo.hold.default-max-per-hold", "4",
                        "kibo.expiration.interval", "PT2S",
                        "kibo.expiration.batch-size", "200",
                        "kibo.cache.drop-ttl", "PT3S",
                        "kibo.seed.enabled", "true"));
    }

    @Test
    void messagingDefaultsArePresentAndTheBrokerFailsFast() {
        assertThat(value("kibo.messaging.enabled")).isEqualTo("true");
        assertThat(value("kibo.messaging.exchange")).isEqualTo("kibo.holds");
        assertThat(value("kibo.messaging.audit-queue")).isEqualTo("kibo.holds.audit");
        assertThat(value("kibo.messaging.audit-consumer-enabled")).isEqualTo("true");
        // A dead broker must cost at most a short connect attempt on the publisher thread (never a request).
        assertThat(value("spring.rabbitmq.connection-timeout")).isEqualTo("1s");
        assertThat(value("spring.rabbitmq.publisher-confirm-type")).isEqualTo("correlated");
    }

    @Test
    void readinessDependsOnDatabaseOnlyAndOnlyHealthIsExposed() {
        assertThat(value("management.endpoint.health.group.readiness.include")).isEqualTo("db");
        assertThat(value("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(value("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(value("management.endpoint.health.show-components")).isEqualTo("always");
        assertThat(value("logging.structured.format.console")).isEqualTo("ecs");
    }

    private static String value(String key) {
        Object raw = yaml.getProperty(key);
        return raw == null ? null : raw.toString();
    }
}
