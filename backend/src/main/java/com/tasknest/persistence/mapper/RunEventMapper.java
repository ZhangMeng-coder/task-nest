package com.tasknest.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tasknest.persistence.entity.RunEventEntity;

/**
 * {@code tn_event} 运行事件账本的 MyBatis-Plus Mapper。
 * <p>事件原文追加与档案回放查询复用 {@code BaseMapper} 通用 CRUD，无自定义 SQL。</p>
 */
public interface RunEventMapper extends BaseMapper<RunEventEntity> {
}
