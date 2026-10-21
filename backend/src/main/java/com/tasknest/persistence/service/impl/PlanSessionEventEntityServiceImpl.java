package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.PlanSessionEventEntity;
import com.tasknest.persistence.mapper.PlanSessionEventMapper;
import com.tasknest.persistence.service.PlanSessionEventEntityService;
import org.springframework.stereotype.Service;

@Service
public class PlanSessionEventEntityServiceImpl
        extends ServiceImpl<PlanSessionEventMapper, PlanSessionEventEntity>
        implements PlanSessionEventEntityService {
}
