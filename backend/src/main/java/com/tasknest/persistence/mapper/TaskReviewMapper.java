package com.tasknest.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tasknest.persistence.entity.TaskReviewEntity;

/**
 * {@code tn_task_review} 子任务审核结论的 MyBatis-Plus Mapper。
 * <p>每次审核记录的写入与读取使用 {@code BaseMapper} 通用 CRUD，本接口不增加方法。</p>
 */
public interface TaskReviewMapper extends BaseMapper<TaskReviewEntity> {
}
