package com.tasknest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ExecutorConfig {

    @Bean(name = "orchestrationExecutor", destroyMethod = "shutdownNow")
    public ExecutorService orchestrationExecutor() {
        return Executors.newCachedThreadPool(threadFactory("tasknest-orchestrator"));
    }

    @Bean(name = "sseDispatcher", destroyMethod = "shutdownNow")
    public ExecutorService sseDispatcher() {
        return Executors.newCachedThreadPool(threadFactory("tasknest-sse"));
    }

    @Bean(name = "taskNestScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService taskNestScheduler() {
        return Executors.newScheduledThreadPool(2, threadFactory("tasknest-scheduler"));
    }

    private ThreadFactory threadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
