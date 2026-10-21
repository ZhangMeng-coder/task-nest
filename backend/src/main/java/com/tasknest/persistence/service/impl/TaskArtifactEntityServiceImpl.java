package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.TaskArtifactEntity;
import com.tasknest.persistence.mapper.TaskArtifactMapper;
import com.tasknest.persistence.service.TaskArtifactEntityService;
import org.springframework.stereotype.Service;

@Service
public class TaskArtifactEntityServiceImpl
        extends ServiceImpl<TaskArtifactMapper, TaskArtifactEntity>
        implements TaskArtifactEntityService {
}
