package com.tasknest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池配置：编排、SSE 分发与定时任务使用彼此隔离的线程池。
 *
 * <p>拆分的原因：编排任务会阻塞在模型请求、shell 或审核上，若与 SSE 推送共用线程池，
 * 长任务会拖慢事件推送；心跳与超时又需要独立的定时调度，避免被普通任务占满。
 * 所有线程均为 daemon，容器关闭时通过 {@code shutdownNow} 回收。</p>
 */
@Configuration
public class ExecutorConfig {

    /**
     * 编排线程池：承载主 / 子 Agent 执行与规划会话，任务可能长时间阻塞，因此使用缓存线程池。
     */
    @Bean(name = "orchestrationExecutor", destroyMethod = "shutdownNow")
    public ExecutorService orchestrationExecutor() {
        return Executors.newCachedThreadPool(threadFactory("tasknest-orchestrator"));
    }

    /**
     * SSE 分发线程池：单独推送事件，避免被编排长任务阻塞。
     */
    @Bean(name = "sseDispatcher", destroyMethod = "shutdownNow")
    public ExecutorService sseDispatcher() {
        return Executors.newCachedThreadPool(threadFactory("tasknest-sse"));
    }

    /**
     * 定时调度线程池：用于 SSE 心跳、持久化重试等延时 / 周期任务，固定两个线程。
     */
    @Bean(name = "taskNestScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService taskNestScheduler() {
        return Executors.newScheduledThreadPool(2, threadFactory("tasknest-scheduler"));
    }

    /**
     * 创建带前缀、daemon 标记的线程工厂，便于日志定位与进程退出。
     */
    private ThreadFactory threadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
