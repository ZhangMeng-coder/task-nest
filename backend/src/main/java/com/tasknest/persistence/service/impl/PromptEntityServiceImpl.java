package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.PromptEntity;
import com.tasknest.persistence.mapper.PromptMapper;
import com.tasknest.persistence.service.PromptEntityService;
import org.springframework.stereotype.Service;

/**
 * {@code PromptEntityService} 的 MyBatis-Plus 实现，绑定提示词记录 Mapper。
 * <p>每次提示词拼装的保存与档案读取继承自 {@code ServiceImpl} 的通用 CRUD。</p>
 */
@Service
public class PromptEntityServiceImpl
        extends ServiceImpl<PromptMapper, PromptEntity>
        implements PromptEntityService {
}
