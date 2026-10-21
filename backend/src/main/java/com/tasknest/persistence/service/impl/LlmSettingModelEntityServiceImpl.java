package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.LlmSettingModelEntity;
import com.tasknest.persistence.mapper.LlmSettingModelMapper;
import com.tasknest.persistence.service.LlmSettingModelEntityService;
import org.springframework.stereotype.Service;

@Service
public class LlmSettingModelEntityServiceImpl
        extends ServiceImpl<LlmSettingModelMapper, LlmSettingModelEntity>
        implements LlmSettingModelEntityService {
}
