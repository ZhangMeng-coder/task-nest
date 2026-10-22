package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.PlanSessionEntity;
import com.tasknest.persistence.mapper.PlanSessionMapper;
import com.tasknest.persistence.service.PlanSessionEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code PlanSessionEntityService} 的 MyBatis-Plus 实现，绑定规划会话 Mapper。
 * <p>会话状态落库使用 {@code ServiceImpl} 的通用 CRUD；生命周期仍由会话存储编排。</p>
 */
@Service
public class PlanSessionEntityServiceImpl
        extends ServiceImpl<PlanSessionMapper, PlanSessionEntity>
        implements PlanSessionEntityService {
}
