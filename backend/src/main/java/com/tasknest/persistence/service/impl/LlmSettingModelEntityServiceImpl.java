package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.LlmSettingModelEntity;
import com.tasknest.persistence.mapper.LlmSettingModelMapper;
import com.tasknest.persistence.service.LlmSettingModelEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code LlmSettingModelEntityService} 的 MyBatis-Plus 实现，绑定模型名称设置 Mapper。
 * <p>与连接设置配套的模型名单例记录沿用 {@code ServiceImpl} 的通用 CRUD。</p>
 */
@Service
public class LlmSettingModelEntityServiceImpl
        extends ServiceImpl<LlmSettingModelMapper, LlmSettingModelEntity>
        implements LlmSettingModelEntityService {
}
