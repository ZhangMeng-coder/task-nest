package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.TaskReviewEntity;
import com.tasknest.persistence.mapper.TaskReviewMapper;
import com.tasknest.persistence.service.TaskReviewEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code TaskReviewEntityService} 的 MyBatis-Plus 实现，绑定审核结论 Mapper。
 * <p>审核记录的保存与读取使用 {@code ServiceImpl} 通用 CRUD，不扩展持久化行为。</p>
 */
@Service
public class TaskReviewEntityServiceImpl
        extends ServiceImpl<TaskReviewMapper, TaskReviewEntity>
        implements TaskReviewEntityService {
}
