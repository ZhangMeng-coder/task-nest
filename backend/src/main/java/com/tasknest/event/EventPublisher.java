package com.tasknest.event;

import com.tasknest.persistence.RunPersistenceService;
import com.tasknest.store.RunState;
import com.tasknest.store.TaskProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 运行事件的唯一发布入口：分配 seq、组装信封、更新投影、落库并扇出到 SSE。
 *
 * <p>发布在运行锁内完成，顺序固定为：seq → envelope → 内存事件列表
 * → {@link TaskProjection} 投影 → MySQL 持久化 → SSE 入队。投影在锁内完成，
 * 保证快照与事件流看到一致状态。</p>
 *
 * <p>终止事件（{@code run_completed} / {@code error}）入队后立即要求连接刷完即关；
 * 持久化失败只记录错误，不阻断 SSE，也不回滚内存状态。</p>
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final TaskProjection projection;
    private final RunPersistenceService persistence;

    public EventPublisher(TaskProjection projection,
                          RunPersistenceService persistence) {
        this.projection = projection;
        this.persistence = persistence;
    }

    /**
     * 发布事件并返回已分配 seq 的信封。eventId 由 seq 推导。
     */
    public EventEnvelope publish(RunState run, EventType type, TaskRef task, EventPayload payload) {
        EventEnvelope event;
        synchronized (run.getLock()) {
            long seq = run.nextSeq();
            event = new EventEnvelope(
                    String.format("evt_%06d", seq),
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

            run.enqueueSubscribers(event);
            if (type.isTerminal()) {
                run.closeSubscribersAfterFlush();
            }
        }
        return event;
    }

    /**
     * 发布心跳；心跳同样占用 seq，保证补发游标连续。
     */
    public EventEnvelope publishHeartbeat(RunState run) {
        return publish(run, EventType.HEARTBEAT, null, new EventPayload.Empty());
    }
}
