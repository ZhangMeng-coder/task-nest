package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.PromptEntity;
import com.tasknest.persistence.mapper.PromptMapper;
import com.tasknest.persistence.service.PromptEntityService;
import org.springframework.stereotype.Service;

@Service
public class PromptEntityServiceImpl
        extends ServiceImpl<PromptMapper, PromptEntity>
        implements PromptEntityService {
}
