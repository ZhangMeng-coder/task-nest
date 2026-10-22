package com.tasknest.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tasknest.persistence.entity.MainTaskPlanEntity;

/**
 * {@code tn_main_task_plan} 主任务计划档案的 MyBatis-Plus Mapper。
 * <p>与主任务一对一的计划读写使用 {@code BaseMapper} 通用 CRUD，无自定义查询。</p>
 */
public interface MainTaskPlanMapper extends BaseMapper<MainTaskPlanEntity> {
}
