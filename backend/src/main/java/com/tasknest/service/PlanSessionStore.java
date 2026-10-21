package com.tasknest.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.event.EventEnvelope;
import com.tasknest.event.EventPayload;
import com.tasknest.event.EventType;
import com.tasknest.event.TaskRef;
import com.tasknest.persistence.entity.PlanSessionEntity;
import com.tasknest.persistence.entity.PlanSessionEventEntity;
import com.tasknest.persistence.service.PlanSessionEntityService;
import com.tasknest.persistence.service.PlanSessionEventEntityService;
import com.tasknest.store.WaitingInputState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规划会话的内存状态 + 事件持久化 + SSE 投递。
 * 规划事件写 tn_plan_session_event，不写 tn_event；确认前不创建主任务。
 */
@Service
public class PlanSessionStore {

    private static final Logger log = LoggerFactory.getLogger(PlanSessionStore.class);

    private final Map<String, PlanSessionState> sessions = new ConcurrentHashMap<>();
    private final PlanSessionEntityService sessionService;
    private final PlanSessionEventEntityService eventService;
    private final ObjectMapper objectMapper;

    public PlanSessionStore(PlanSessionEntityService sessionService,
                            PlanSessionEventEntityService eventService,
                            ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    public PlanSessionState create(String planSessionId,
                                   String goal,
                                   String workingDir,
                                   String mockScript) {
        PlanSessionState state = new PlanSessionState(
                planSessionId, goal, workingDir, mockScript);
        LocalDateTime now = LocalDateTime.now();
        PlanSessionEntity entity = new PlanSessionEntity();
        entity.setPlanSessionId(planSessionId);
        entity.setGoal(goal);
        entity.setWorkingDir(workingDir);
        entity.setMockScript(mockScript);
        entity.setStatus(PlanSessionStatus.RUNNING.name());
        entity.setLastSeq(0L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        sessionService.save(entity);
        sessions.put(planSessionId, state);
        return state;
    }

    public PlanSessionState require(String planSessionId) {
        PlanSessionState state = sessions.get(planSessionId);
        if (state == null) {
            throw new BusinessException(ErrorCode.PLAN_SESSION_NOT_FOUND);
        }
        return state;
    }

    public boolean contains(String planSessionId) {
        return sessions.containsKey(planSessionId);
    }

    public void remove(String planSessionId) {
        sessions.remove(planSessionId);
    }

    public EventEnvelope publish(PlanSessionState state,
                                 EventType type,
                                 TaskRef task,
                                 EventPayload payload) {
        EventEnvelope event;
        synchronized (state.getLock()) {
            long seq = state.nextSeq();
            event = new EventEnvelope(
                    String.format("evt_%06d", seq),
                    state.getPlanSessionId(),
                    seq,
                    type,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    task,
                    payload == null ? new EventPayload.Empty() : payload);
            state.appendEvent(event);
            state.setUpdatedAt(event.timestamp());
            applySemantics(state, event);
            persistEvent(state, event);
            persistSession(state);
            if (event.type().isTerminal()) {
                state.enqueueSubscribers(event);
                state.closeSubscribersAfterFlush();
            } else {
                state.enqueueSubscribers(event);
            }
        }
        return event;
    }

    public EventEnvelope publishHeartbeat(PlanSessionState state) {
        return publish(state, EventType.HEARTBEAT, null, new EventPayload.Empty());
    }

    public void updateStatus(PlanSessionState state,
                             PlanSessionStatus status,
                             String error) {
        synchronized (state.getLock()) {
            state.setStatus(status);
            if (error != null) {
                state.setError(error);
            }
            state.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            persistSession(state);
        }
    }

    public void setWaiting(PlanSessionState state, WaitingInputState waiting) {
        synchronized (state.getLock()) {
            state.setWaitingInput(waiting);
        }
    }

    public void clearWaiting(PlanSessionState state, WaitingInputState waiting) {
        synchronized (state.getLock()) {
            if (state.getWaitingInput() == waiting) {
                state.setWaitingInput(null);
            }
        }
    }

    private void applySemantics(PlanSessionState state, EventEnvelope event) {
        switch (event.type()) {
            case PLAN -> {
                if (event.payload() instanceof EventPayload.Plan plan) {
                    state.setPlan(plan);
                    state.setPlanJson(writeJson(plan));
                    state.setStatus(PlanSessionStatus.READY);
                    state.setError(null);
                }
            }
            case WAITING_INPUT -> state.setStatus(PlanSessionStatus.WAITING_INPUT);
            case INPUT_RECEIVED -> state.setStatus(PlanSessionStatus.RUNNING);
            case ERROR -> {
                state.setStatus(PlanSessionStatus.FAILED);
                if (event.payload() instanceof EventPayload.Error error) {
                    state.setError(error.message());
                }
            }
            case RUN_COMPLETED -> {
                // 状态由确认 / 取消流程先行设置。
            }
            default -> {
            }
        }
    }

    private void persistEvent(PlanSessionState state, EventEnvelope event) {
        try {
            PlanSessionEventEntity entity = new PlanSessionEventEntity();
            entity.setPlanSessionId(state.getPlanSessionId());
            entity.setEventId(event.eventId());
            entity.setSeq(event.seq());
            entity.setType(event.type().wireName());
            entity.setEventJson(writeJson(event));
            entity.setEventTime(toLocal(event.timestamp()));
            entity.setCreatedAt(toLocal(event.timestamp()));
            eventService.save(entity);
        } catch (RuntimeException exception) {
            log.error("规划事件持久化失败 session={} seq={}",
                    state.getPlanSessionId(), event.seq(), exception);
        }
    }

    private void persistSession(PlanSessionState state) {
        try {
            PlanSessionEntity entity = sessionService.getOne(
                    Wrappers.<PlanSessionEntity>lambdaQuery()
                            .eq(PlanSessionEntity::getPlanSessionId,
                                    state.getPlanSessionId()));
            if (entity == null) {
                return;
            }
            entity.setStatus(state.getStatus().name());
            entity.setPlanJson(state.getPlanJson());
            entity.setLastSeq(state.getLastSeq());
            entity.setError(state.getError());
            entity.setUpdatedAt(toLocal(state.getUpdatedAt()));
            sessionService.updateById(entity);
        } catch (RuntimeException exception) {
            log.error("规划会话持久化失败 session={}", state.getPlanSessionId(), exception);
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.warn("无法序列化规划内容", exception);
            return null;
        }
    }

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
