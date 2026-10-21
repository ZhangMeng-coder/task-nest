package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.AgentSettingEntity;
import com.tasknest.persistence.mapper.AgentSettingMapper;
import com.tasknest.persistence.service.AgentSettingEntityService;
import org.springframework.stereotype.Service;

@Service
public class AgentSettingEntityServiceImpl
        extends ServiceImpl<AgentSettingMapper, AgentSettingEntity>
        implements AgentSettingEntityService {
}
