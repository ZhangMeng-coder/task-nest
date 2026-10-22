package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.LlmSettingEntity;
import com.tasknest.persistence.mapper.LlmSettingMapper;
import com.tasknest.persistence.service.LlmSettingEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code LlmSettingEntityService} 的 MyBatis-Plus 实现，绑定 LLM 连接设置 Mapper。
 * <p>通用单表操作由 {@code ServiceImpl} 提供，敏感字段处理不在此层展开。</p>
 */
@Service
public class LlmSettingEntityServiceImpl
        extends ServiceImpl<LlmSettingMapper, LlmSettingEntity>
        implements LlmSettingEntityService {
}
