package com.kibo.reservation.messaging;

import com.kibo.reservation.config.MessagingProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the topology: topic exchange {@code kibo.holds} and the durable demo queue
 * {@code kibo.holds.audit} bound with {@code hold.#}.
 *
 * <p>Spring Boot's {@code RabbitAdmin} declares these whenever it opens a connection, so a broker that is
 * down at startup does not fail the service, and the topology appears by itself once the broker is
 * reachable (and again after a broker restart that lost it).
 */
@Configuration
@ConditionalOnProperty(prefix = "kibo.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
class RabbitMessagingConfig {

    /** Matches every hold event ({@code hold.created}, {@code hold.confirmed}, ...). */
    static final String ALL_HOLD_EVENTS = "hold.#";

    @Bean
    Declarables holdEventTopology(MessagingProperties properties) {
        TopicExchange exchange = new TopicExchange(properties.exchange(), true, false);
        Queue auditQueue = QueueBuilder.durable(properties.auditQueue()).build();
        Binding binding = BindingBuilder.bind(auditQueue).to(exchange).with(ALL_HOLD_EVENTS);
        return new Declarables(exchange, auditQueue, binding);
    }
}
