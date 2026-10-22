package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.PlanSessionEventEntity;
import com.tasknest.persistence.mapper.PlanSessionEventMapper;
import com.tasknest.persistence.service.PlanSessionEventEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code PlanSessionEventEntityService} 的 MyBatis-Plus 实现，绑定规划事件 Mapper。
 * <p>规划事件追加与补发查询直接复用 {@code ServiceImpl}，不加入自定义 SQL。</p>
 */
@Service
public class PlanSessionEventEntityServiceImpl
        extends ServiceImpl<PlanSessionEventMapper, PlanSessionEventEntity>
        implements PlanSessionEventEntityService {
}
