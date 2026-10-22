package com.tasknest.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code tn_plan_session_event} 规划会话事件原文，按 {@code (plan_session_id, seq)} 唯一。
 * <p>{@code PlanSessionStore} 在规划 SSE 推送前写入，供刷新与断线重连按游标补发；
 * {@code event_json} 以 LONGTEXT 保存完整事件信封 JSON，{@code event_time} 为事件产生时间。</p>
 */
@TableName("tn_plan_session_event")
public class PlanSessionEventEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String planSessionId;
    private String eventId;
    private Long seq;
    private String type;
    private String eventJson;
    private LocalDateTime eventTime;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlanSessionId() {
        return planSessionId;
    }

    public void setPlanSessionId(String planSessionId) {
        this.planSessionId = planSessionId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getEventJson() {
        return eventJson;
    }

    public void setEventJson(String eventJson) {
        this.eventJson = eventJson;
    }

    public LocalDateTime getEventTime() {
        return eventTime;
    }

    public void setEventTime(LocalDateTime eventTime) {
        this.eventTime = eventTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
