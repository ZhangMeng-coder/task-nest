package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.TaskArtifactEntity;

/**
 * {@code tn_task_artifact} 任务中间结果的服务契约。
 * <p>思考与工具证据的保存、更新继承自 {@code IService}，不声明自定义查询。</p>
 */
public interface TaskArtifactEntityService extends IService<TaskArtifactEntity> {
}
