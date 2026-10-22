package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.SubtaskEntity;

/**
 * {@code tn_subtask} 直属子任务的服务契约。
 * <p>子任务投影与档案查询复用 {@code IService} 单表 CRUD；两层约束由事件映射保证。</p>
 */
public interface SubtaskEntityService extends IService<SubtaskEntity> {
}
