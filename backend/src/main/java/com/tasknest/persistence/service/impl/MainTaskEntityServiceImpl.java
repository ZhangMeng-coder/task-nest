package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.MainTaskEntity;
import com.tasknest.persistence.mapper.MainTaskMapper;
import com.tasknest.persistence.service.MainTaskEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code MainTaskEntityService} 的 MyBatis-Plus 实现，绑定主任务档案 Mapper。
 * <p>主任务保存、更新与查询直接使用 {@code ServiceImpl} 的单表 CRUD。</p>
 */
@Service
public class MainTaskEntityServiceImpl
        extends ServiceImpl<MainTaskMapper, MainTaskEntity>
        implements MainTaskEntityService {
}
