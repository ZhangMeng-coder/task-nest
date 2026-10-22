package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.MainTaskPlanEntity;
import com.tasknest.persistence.mapper.MainTaskPlanMapper;
import com.tasknest.persistence.service.MainTaskPlanEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code MainTaskPlanEntityService} 的 MyBatis-Plus 实现，绑定计划表 Mapper。
 * <p>计划草案和档案修订的持久化复用 {@code ServiceImpl}，无额外数据库操作。</p>
 */
@Service
public class MainTaskPlanEntityServiceImpl
        extends ServiceImpl<MainTaskPlanMapper, MainTaskPlanEntity>
        implements MainTaskPlanEntityService {
}
