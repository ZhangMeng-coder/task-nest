package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.MainTaskWorkspaceEntity;
import com.tasknest.persistence.mapper.MainTaskWorkspaceMapper;
import com.tasknest.persistence.service.MainTaskWorkspaceEntityService;
import org.springframework.stereotype.Service;

@Service
public class MainTaskWorkspaceEntityServiceImpl
        extends ServiceImpl<MainTaskWorkspaceMapper, MainTaskWorkspaceEntity>
        implements MainTaskWorkspaceEntityService {
}
