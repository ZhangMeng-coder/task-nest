package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.PlanSessionEventEntity;

/**
 * {@code tn_plan_session_event} 规划事件原文的服务契约。
 * <p>事件追加和补发查询使用 {@code IService} 通用 CRUD，接口本身不增加方法。</p>
 */
public interface PlanSessionEventEntityService extends IService<PlanSessionEventEntity> {
}
