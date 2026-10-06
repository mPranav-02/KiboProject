package com.kibo.reservation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The event-publisher pool is bounded, and a full queue drops events instead of blocking or throwing. */
class AsyncConfigTest {

    @Test
    void theExecutorIsBoundedAsDesigned() {
        ThreadPoolTaskExecutor executor = newExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(1000);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void whenSaturatedExtraEventsAreDroppedAndNeverRunOrThrowOnTheCaller() throws Exception {
        ThreadPoolTaskExecutor executor = newExecutor();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        Runnable stuckBrokerTask = () -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran.incrementAndGet();
        };
        int capacity = 2 + 1000 + 2; // core threads + queue + extra threads up to the maximum
        try {
            for (int i = 0; i < capacity; i++) {
                executor.execute(stuckBrokerTask);
            }

            // Pool and queue are completely full: further events are rejected, dropped and logged.
            assertThatCode(() -> {
                for (int i = 0; i < 50; i++) {
                    executor.execute(stuckBrokerTask);
                }
            }).doesNotThrowAnyException();
        } finally {
            release.countDown();
            executor.shutdown();
        }
        assertThat(executor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).as("accepted tasks ran once, the 50 dropped ones never ran").isEqualTo(capacity);
    }

    private static ThreadPoolTaskExecutor newExecutor() {
        ThreadPoolTaskExecutor executor = new AsyncConfig().eventPublisherExecutor();
        executor.initialize();
        return executor;
    }
}
