package com.kibo.reservation.config;

import java.util.concurrent.RejectedExecutionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The bounded executor lifecycle events are published on. Publishing is fire-and-forget after the commit,
 * so a slow or dead broker may only ever cost this pool: the queue is bounded (a stuck broker cannot grow
 * memory without limit) and when it is full the event is DROPPED and logged, never run on the request
 * thread. Requests are never blocked and hold/inventory state is never affected (FR-027).
 */
@Configuration
public class AsyncConfig {

    static final int CORE_THREADS = 2;
    static final int MAX_THREADS = 4;
    static final int QUEUE_CAPACITY = 1000;

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "eventPublisherExecutor")
    ThreadPoolTaskExecutor eventPublisherExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_THREADS);
        executor.setMaxPoolSize(MAX_THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("event-publisher-");
        executor.setRejectedExecutionHandler(discardAndLog());
        // Let queued events still go out on a clean shutdown, but never hang shutdown on a dead broker.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    static RejectedExecutionHandler discardAndLog() {
        return (task, pool) -> log.warn("Event publisher queue is full ({} waiting): lifecycle event dropped. "
                + "The hold itself is unaffected; MySQL remains the source of truth.", pool.getQueue().size());
    }
}
