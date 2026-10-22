package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.MainTaskEntity;

/**
 * {@code tn_main_task} 主任务档案的服务契约。
 * <p>主任务创建、状态投影与档案查询共用 {@code IService} 的单表 CRUD 能力。</p>
 */
public interface MainTaskEntityService extends IService<MainTaskEntity> {
}
