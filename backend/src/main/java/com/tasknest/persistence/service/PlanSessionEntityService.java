package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.PlanSessionEntity;

/**
 * {@code tn_plan_session} 规划会话的服务契约。
 * <p>会话创建与状态更新复用 {@code IService} 的单表 CRUD，生命周期编排留在会话存储层。</p>
 */
public interface PlanSessionEntityService extends IService<PlanSessionEntity> {
}
