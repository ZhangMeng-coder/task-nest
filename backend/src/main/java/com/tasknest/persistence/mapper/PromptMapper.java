package com.tasknest.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tasknest.persistence.entity.PromptEntity;

/**
 * {@code tn_prompt} 提示词拼装记录的 MyBatis-Plus Mapper。
 * <p>每轮 {@code prompt_assembled} 的持久化使用 {@code BaseMapper} 通用 CRUD。</p>
 */
public interface PromptMapper extends BaseMapper<PromptEntity> {
}
