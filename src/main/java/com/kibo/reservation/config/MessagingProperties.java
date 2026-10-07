package com.kibo.reservation.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the RabbitMQ lifecycle-event adapter under {@code kibo.messaging.*} (defaults in
 * application.yml, each overridable through the environment, e.g. {@code KIBO_MESSAGING_EXCHANGE}).
 * The broker's host, port and credentials are NOT here: they come from {@code RABBITMQ_*}
 * (Constitution XIV).
 *
 * @param enabled              publish hold lifecycle events at all. Turning it off removes the whole adapter
 *                             (publisher, topology declaration, demo consumer); the service behaves
 *                             identically, because nothing in the hold path depends on it.
 * @param exchange             durable topic exchange events are published to
 * @param auditQueue           durable demo queue bound with {@code hold.#}, so the topology is visible
 * @param auditConsumerEnabled run the demo consumer that logs what arrives on the audit queue
 */
@Validated
@ConfigurationProperties(prefix = "kibo.messaging")
public record MessagingProperties(
        boolean enabled,
        @NotBlank String exchange,
        @NotBlank String auditQueue,
        boolean auditConsumerEnabled) {
}
