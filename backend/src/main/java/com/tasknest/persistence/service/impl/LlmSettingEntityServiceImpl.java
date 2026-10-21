package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.LlmSettingEntity;
import com.tasknest.persistence.mapper.LlmSettingMapper;
import com.tasknest.persistence.service.LlmSettingEntityService;
import org.springframework.stereotype.Service;

@Service
public class LlmSettingEntityServiceImpl
        extends ServiceImpl<LlmSettingMapper, LlmSettingEntity>
        implements LlmSettingEntityService {
}
