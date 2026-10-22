package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.RunEventEntity;
import com.tasknest.persistence.mapper.RunEventMapper;
import com.tasknest.persistence.service.RunEventEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code RunEventEntityService} 的 MyBatis-Plus 实现，绑定运行事件 Mapper。
 * <p>事件账本的单表写入和读取由 {@code ServiceImpl} 提供，无自定义实现。</p>
 */
@Service
public class RunEventEntityServiceImpl
        extends ServiceImpl<RunEventMapper, RunEventEntity>
        implements RunEventEntityService {
}
