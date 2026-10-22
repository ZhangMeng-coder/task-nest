package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.RunEventEntity;

/**
 * {@code tn_event} 运行事件账本的服务契约。
 * <p>事件原文写入与按序号回放依赖 {@code IService} 提供的通用单表操作。</p>
 */
public interface RunEventEntityService extends IService<RunEventEntity> {
}
