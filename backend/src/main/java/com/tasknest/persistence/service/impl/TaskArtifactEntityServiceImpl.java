package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.TaskArtifactEntity;
import com.tasknest.persistence.mapper.TaskArtifactMapper;
import com.tasknest.persistence.service.TaskArtifactEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code TaskArtifactEntityService} 的 MyBatis-Plus 实现，绑定中间结果 Mapper。
 * <p>思考与工具证据记录的增改查由 {@code ServiceImpl} 提供。</p>
 */
@Service
public class TaskArtifactEntityServiceImpl
        extends ServiceImpl<TaskArtifactMapper, TaskArtifactEntity>
        implements TaskArtifactEntityService {
}
