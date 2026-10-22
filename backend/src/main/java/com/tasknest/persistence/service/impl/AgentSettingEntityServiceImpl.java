package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.AgentSettingEntity;
import com.tasknest.persistence.mapper.AgentSettingMapper;
import com.tasknest.persistence.service.AgentSettingEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code AgentSettingEntityService} 的 MyBatis-Plus 实现，绑定审核设置 Mapper。
 * <p>单例设置记录的 CRUD 直接复用 {@code ServiceImpl}，不添加自定义方法。</p>
 */
@Service
public class AgentSettingEntityServiceImpl
        extends ServiceImpl<AgentSettingMapper, AgentSettingEntity>
        implements AgentSettingEntityService {
}
