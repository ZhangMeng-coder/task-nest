package com.tasknest.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.config.TaskNestProperties;
import com.tasknest.event.EventEnvelope;
import com.tasknest.event.EventPublisher;
import com.tasknest.store.RunState;
import com.tasknest.store.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class SseService {

    private static final Logger log = LoggerFactory.getLogger(SseService.class);

    private final RunStore runStore;
    private final EventPublisher eventPublisher;
    private final TaskNestProperties properties;
    private final ExecutorService sseDispatcher;
    private final ScheduledExecutorService scheduler;
    private final ObjectMapper objectMapper;
    private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    public SseService(RunStore runStore,
                      EventPublisher eventPublisher,
                      TaskNestProperties properties,
                      @Qualifier("sseDispatcher") ExecutorService sseDispatcher,
                      @Qualifier("taskNestScheduler") ScheduledExecutorService scheduler,
                      ObjectMapper objectMapper) {
        this.runStore = runStore;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.sseDispatcher = sseDispatcher;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    public SseEmitter connect(String runId, long afterSeq) {
        RunState run = runStore.require(runId);
        SseConnection connection;
        synchronized (run.getLock()) {
            long lastSeq = run.getLastSeq();
            if (afterSeq < 0 || afterSeq > lastSeq) {
                throw new BusinessException(ErrorCode.INVALID_AFTER_SEQ,
                        "afterSeq 必须在 0 到 " + lastSeq + " 之间");
            }
            connection = new SseConnection(
                    properties.getSse().getTimeoutMs(),
                    sseDispatcher,
                    objectMapper,
                    closed -> removeConnection(run, closed));
            connection.setReplayBoundary(lastSeq);
            for (EventEnvelope event : run.getEventsCopy()) {
                if (event.seq() > afterSeq) {
                    connection.enqueueReplay(event);
                }
            }
            run.addSubscriber(connection);
            connection.start();

            // D-01: every connection gets an immediate heartbeat, then the run-wide 15s cadence.
            eventPublisher.publishHeartbeat(run);

            if (run.isTerminal()) {
                connection.closeAfterFlush();
            } else {
                ensureHeartbeat(run);
            }
        }
        return connection.getEmitter();
    }

    private void removeConnection(RunState run, SseConnection connection) {
        synchronized (run.getLock()) {
            run.removeSubscriber(connection);
            if (run.getSubscriberCount() == 0) {
                cancelHeartbeat(run.getRunId());
            }
        }
    }

    private void ensureHeartbeat(RunState run) {
        if (run.isTerminal()) {
            return;
        }
        long interval = properties.getSse().getHeartbeatMs();
        heartbeatTasks.computeIfAbsent(run.getRunId(), id -> scheduler.scheduleAtFixedRate(() -> {
            try {
                if (run.isTerminal() || run.getSubscriberCount() == 0) {
                    cancelHeartbeat(id);
                    return;
                }
                eventPublisher.publishHeartbeat(run);
            } catch (RuntimeException exception) {
                log.warn("Heartbeat failed for run {}", id, exception);
            }
        }, interval, interval, TimeUnit.MILLISECONDS));
    }

    private void cancelHeartbeat(String runId) {
        ScheduledFuture<?> task = heartbeatTasks.remove(runId);
        if (task != null) {
            task.cancel(false);
        }
    }
}
