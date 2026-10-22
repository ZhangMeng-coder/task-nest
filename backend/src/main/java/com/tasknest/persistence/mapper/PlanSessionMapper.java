package com.tasknest.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tasknest.persistence.entity.PlanSessionEntity;

/**
 * {@code tn_plan_session} 规划会话的 MyBatis-Plus Mapper。
 * <p>会话创建、状态更新与恢复查询均通过 {@code BaseMapper} 的单表能力实现。</p>
 */
public interface PlanSessionMapper extends BaseMapper<PlanSessionEntity> {
}
