package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.TaskReviewEntity;
import com.tasknest.persistence.mapper.TaskReviewMapper;
import com.tasknest.persistence.service.TaskReviewEntityService;
import org.springframework.stereotype.Service;

@Service
public class TaskReviewEntityServiceImpl
        extends ServiceImpl<TaskReviewMapper, TaskReviewEntity>
        implements TaskReviewEntityService {
}
