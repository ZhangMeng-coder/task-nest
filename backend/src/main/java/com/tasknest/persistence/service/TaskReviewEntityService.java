package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.TaskReviewEntity;

/**
 * {@code tn_task_review} 审核结论的服务契约。
 * <p>审核记录的写入与读取使用 {@code IService} 通用 CRUD，业务判定留在审核流程。</p>
 */
public interface TaskReviewEntityService extends IService<TaskReviewEntity> {
}
