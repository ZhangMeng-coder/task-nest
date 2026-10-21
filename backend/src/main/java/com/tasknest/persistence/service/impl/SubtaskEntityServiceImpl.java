package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.SubtaskEntity;
import com.tasknest.persistence.mapper.SubtaskMapper;
import com.tasknest.persistence.service.SubtaskEntityService;
import org.springframework.stereotype.Service;

@Service
public class SubtaskEntityServiceImpl
        extends ServiceImpl<SubtaskMapper, SubtaskEntity>
        implements SubtaskEntityService {
}
