package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.MainTaskWorkspaceEntity;

/**
 * {@code tn_main_task_workspace} 工作目录的服务契约。
 * <p>一一对应目录记录的写入与读取继承自 {@code IService}，不额外定义业务方法。</p>
 */
public interface MainTaskWorkspaceEntityService extends IService<MainTaskWorkspaceEntity> {
}
