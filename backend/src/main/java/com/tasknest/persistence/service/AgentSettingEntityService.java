package com.tasknest.persistence.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tasknest.persistence.entity.AgentSettingEntity;

/**
 * {@code tn_agent_setting} 审核设置单例的服务契约。
 * <p>继承 {@code IService} 获得单表 CRUD；具体实现由
 * {@code AgentSettingEntityServiceImpl} 提供，供设置服务调用。</p>
 */
public interface AgentSettingEntityService extends IService<AgentSettingEntity> {
}
