package com.tasknest.persistence.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tasknest.persistence.entity.MainTaskEntity;
import com.tasknest.persistence.mapper.MainTaskMapper;
import com.tasknest.persistence.service.MainTaskEntityService;
import org.springframework.stereotype.Service;

@Service
public class MainTaskEntityServiceImpl
        extends ServiceImpl<MainTaskMapper, MainTaskEntity>
        implements MainTaskEntityService {
}
