package com.tasknest.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tasknest.persistence.entity.MainTaskWorkspaceEntity;

/**
 * {@code tn_main_task_workspace} 主任务工作目录的 MyBatis-Plus Mapper。
 * <p>目录记录的保存与按主任务读取通过 {@code BaseMapper} 单表能力完成。</p>
 */
public interface MainTaskWorkspaceMapper extends BaseMapper<MainTaskWorkspaceEntity> {
}
