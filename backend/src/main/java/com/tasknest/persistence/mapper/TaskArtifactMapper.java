package com.tasknest.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tasknest.persistence.entity.TaskArtifactEntity;

/**
 * {@code tn_task_artifact} 任务中间结果的 MyBatis-Plus Mapper。
 * <p>思考、工具调用与结果的增改查继承自 {@code BaseMapper}，无额外 SQL 声明。</p>
 */
public interface TaskArtifactMapper extends BaseMapper<TaskArtifactEntity> {
}
