package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.MainTaskPlanEntity;

/**
 * {@code tn_main_task_plan} 计划档案的服务契约。
 * <p>计划保存、读取与修订更新使用 {@code IService} 提供的通用单表方法，无附加接口。</p>
 */
public interface MainTaskPlanEntityService extends IService<MainTaskPlanEntity> {
}
