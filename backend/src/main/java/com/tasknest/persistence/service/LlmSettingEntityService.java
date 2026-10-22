package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.LlmSettingEntity;

/**
 * {@code tn_llm_setting} LLM 连接设置单例的服务契约。
 * <p>沿用 {@code IService} 的通用 CRUD，不额外声明查询；密钥处理逻辑留在服务层。</p>
 */
public interface LlmSettingEntityService extends IService<LlmSettingEntity> {
}
