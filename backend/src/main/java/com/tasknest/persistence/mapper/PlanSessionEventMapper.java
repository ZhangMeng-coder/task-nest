package com.tasknest.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tasknest.persistence.entity.PlanSessionEventEntity;

/**
 * {@code tn_plan_session_event} 规划会话事件原文的 MyBatis-Plus Mapper。
 * <p>事件追加与重连补发复用 {@code BaseMapper} 的基础 CRUD，本接口不定义 SQL。</p>
 */
public interface PlanSessionEventMapper extends BaseMapper<PlanSessionEventEntity> {
}
