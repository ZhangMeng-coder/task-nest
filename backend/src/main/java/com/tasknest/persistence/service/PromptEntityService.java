package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.PromptEntity;

/**
 * {@code tn_prompt} 提示词拼装记录的服务契约。
 * <p>提示词行的保存与档案读取通过 {@code IService} 基础 CRUD 完成。</p>
 */
public interface PromptEntityService extends IService<PromptEntity> {
}
