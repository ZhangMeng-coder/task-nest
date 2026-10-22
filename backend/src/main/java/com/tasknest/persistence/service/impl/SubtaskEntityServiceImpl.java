package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.SubtaskEntity;
import com.tasknest.persistence.mapper.SubtaskMapper;
import com.tasknest.persistence.service.SubtaskEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code SubtaskEntityService} 的 MyBatis-Plus 实现，绑定子任务档案 Mapper。
 * <p>两层任务投影中的子任务 CRUD 直接复用 {@code ServiceImpl} 的基础方法。</p>
 */
@Service
public class SubtaskEntityServiceImpl
        extends ServiceImpl<SubtaskMapper, SubtaskEntity>
        implements SubtaskEntityService {
}
