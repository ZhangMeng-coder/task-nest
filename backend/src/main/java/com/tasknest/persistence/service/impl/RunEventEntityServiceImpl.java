package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.RunEventEntity;
import com.tasknest.persistence.mapper.RunEventMapper;
import com.tasknest.persistence.service.RunEventEntityService;
import org.springframework.stereotype.Service;

@Service
public class RunEventEntityServiceImpl
        extends ServiceImpl<RunEventMapper, RunEventEntity>
        implements RunEventEntityService {
}
