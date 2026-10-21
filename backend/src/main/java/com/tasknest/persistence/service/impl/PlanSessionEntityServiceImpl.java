package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.PlanSessionEntity;
import com.tasknest.persistence.mapper.PlanSessionMapper;
import com.tasknest.persistence.service.PlanSessionEntityService;
import org.springframework.stereotype.Service;

@Service
public class PlanSessionEntityServiceImpl
        extends ServiceImpl<PlanSessionMapper, PlanSessionEntity>
        implements PlanSessionEntityService {
}
