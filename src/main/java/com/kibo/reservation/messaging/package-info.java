/**
 * Messaging adapter: publishes hold lifecycle events to RabbitMQ after the database commit.
 *
 * <p>Nothing outside this package refers to it. The business code raises an in-process
 * {@code HoldLifecycleEvent} and this package reacts to it, so messaging can be removed
 * ({@code kibo.messaging.enabled=false}) or broken without changing what the service does.
 */
package com.kibo.reservation.messaging;
