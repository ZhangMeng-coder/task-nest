package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.MainTaskPlanEntity;
import com.tasknest.persistence.mapper.MainTaskPlanMapper;
import com.tasknest.persistence.service.MainTaskPlanEntityService;
import org.springframework.stereotype.Service;

@Service
public class MainTaskPlanEntityServiceImpl
        extends ServiceImpl<MainTaskPlanMapper, MainTaskPlanEntity>
        implements MainTaskPlanEntityService {
}
