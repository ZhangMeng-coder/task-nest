package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.MainTaskWorkspaceEntity;
import com.tasknest.persistence.mapper.MainTaskWorkspaceMapper;
import com.tasknest.persistence.service.MainTaskWorkspaceEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code MainTaskWorkspaceEntityService} 的 MyBatis-Plus 实现，绑定工作目录 Mapper。
 * <p>一一对应目录记录的保存与读取由 {@code ServiceImpl} 提供。</p>
 */
@Service
public class MainTaskWorkspaceEntityServiceImpl
        extends ServiceImpl<MainTaskWorkspaceMapper, MainTaskWorkspaceEntity>
        implements MainTaskWorkspaceEntityService {
}
