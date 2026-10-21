package com.tasknest.event;

import com.tasknest.persistence.RunPersistenceService;
import com.tasknest.store.RunState;
import com.tasknest.store.TaskProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final TaskProjection projection;
    private final RunPersistenceService persistence;
    private final ScheduledExecutorService scheduler;

    public EventPublisher(TaskProjection projection,
                          RunPersistenceService persistence,
                          @Qualifier("taskNestScheduler") ScheduledExecutorService scheduler) {
        this.projection = projection;
        this.persistence = persistence;
        this.scheduler = scheduler;
    }

    public EventEnvelope publish(RunState run, EventType type, TaskRef task, EventPayload payload) {
        return publish(run, type, task, payload, null, 0, false);
    }

    public EventEnvelope publish(RunState run,
                                 EventType type,
                                 TaskRef task,
                                 EventPayload payload,
                                 String explicitEventId,
                                 long dispatchDelayMs,
                                 boolean dropDispatch) {
        boolean terminal = type.isTerminal();
        long effectiveDelay = terminal ? 0 : Math.max(0, dispatchDelayMs);
        boolean effectiveDrop = terminal ? false : dropDispatch;

        EventEnvelope event;
        synchronized (run.getLock()) {
            long seq = run.nextSeq();
            String eventId = (explicitEventId == null || explicitEventId.isBlank())
                    ? String.format("evt_%06d", seq)
                    : explicitEventId;
            event = new EventEnvelope(
                    eventId,
                    run.getRunId(),
                    seq,
                    type,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    task,
                    payload == null ? new EventPayload.Empty() : payload);
            run.appendEvent(event);
            run.setUpdatedAt(event.timestamp());
            projection.apply(run, event);
            try {
                persistence.persist(run, event);
            } catch (RuntimeException exception) {
                log.error("事件持久化失败 run={} seq={} type={}",
                        run.getRunId(), event.seq(), event.type().wireName(), exception);
            }

            if (terminal) {
                run.enqueueSubscribers(event);
                run.closeSubscribersAfterFlush();
            } else if (!effectiveDrop) {
                if (effectiveDelay <= 0) {
                    run.enqueueSubscribers(event);
                } else {
                    try {
                        scheduler.schedule(
                                () -> run.enqueueSubscribers(event),
                                effectiveDelay,
                                TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException exception) {
                        log.warn("Scheduler rejected delayed dispatch for {} seq {}", run.getRunId(), event.seq());
                    }
                }
            }
        }
        return event;
    }

    public EventEnvelope publishHeartbeat(RunState run) {
        return publish(run, EventType.HEARTBEAT, null, new EventPayload.Empty());
    }
}
