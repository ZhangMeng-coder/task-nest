package com.tasknest.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tasknest.persistence.entity.SubtaskEntity;

/**
 * {@code tn_subtask} 直属子任务档案的 MyBatis-Plus Mapper。
 * <p>子任务状态、顺序与依赖的读写由 {@code BaseMapper} 提供，本接口不扩展查询。</p>
 */
public interface SubtaskMapper extends BaseMapper<SubtaskEntity> {
}
