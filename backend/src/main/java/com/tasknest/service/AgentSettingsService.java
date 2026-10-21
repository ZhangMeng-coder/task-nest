package com.tasknest.service;

import com.tasknest.api.dto.AgentSettingsView;
import com.tasknest.api.dto.UpdateAgentSettingsRequest;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.persistence.entity.AgentSettingEntity;
import com.tasknest.persistence.service.AgentSettingEntityService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 审核 Agent 全局设置：启动加载，保存后立即替换运行时快照。 */
@Service
public class AgentSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AgentSettingsService.class);
    private static final long SETTING_ID = 1L;

    private final AgentSettingEntityService settingService;
    private volatile CurrentAgentSettings current =
            new CurrentAgentSettings(true, 2, null);

    public AgentSettingsService(AgentSettingEntityService settingService) {
        this.settingService = settingService;
    }

    @PostConstruct
    public void load() {
        try {
            AgentSettingEntity entity = settingService.getById(SETTING_ID);
            current = fromEntity(entity);
            log.info("已从 MySQL 加载 Agent 设置: reviewEnabled={}, reviewMaxReworks={}",
                    current.reviewEnabled(), current.reviewMaxReworks());
        } catch (RuntimeException exception) {
            log.warn("加载 Agent 设置失败，继续使用默认配置", exception);
        }
    }

    public AgentSettingsView view() {
        CurrentAgentSettings snapshot = current;
        return new AgentSettingsView(
                snapshot.reviewEnabled(), snapshot.reviewMaxReworks(), snapshot.updatedAt());
    }

    @Transactional
    public AgentSettingsView update(UpdateAgentSettingsRequest request) {
        if (request.reviewEnabled() == null || request.reviewMaxReworks() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "审核设置不能为空");
        }
        int maxReworks = request.reviewMaxReworks();
        if (maxReworks < 0 || maxReworks > 5) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "reviewMaxReworks 必须在 0 到 5 之间");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentSettingEntity entity = settingService.getById(SETTING_ID);
        if (entity == null) {
            entity = new AgentSettingEntity();
            entity.setId(SETTING_ID);
            entity.setCreatedAt(now);
        }
        entity.setReviewEnabled(request.reviewEnabled());
        entity.setReviewMaxReworks(maxReworks);
        entity.setUpdatedAt(now);
        settingService.saveOrUpdate(entity);
        current = new CurrentAgentSettings(
                request.reviewEnabled(), maxReworks,
                now.atZone(ZoneId.systemDefault()).toInstant());
        return view();
    }

    public CurrentAgentSettings current() {
        return current;
    }

    private CurrentAgentSettings fromEntity(AgentSettingEntity entity) {
        if (entity == null) {
            return new CurrentAgentSettings(true, 2, null);
        }
        boolean enabled = entity.getReviewEnabled() == null || entity.getReviewEnabled();
        int maxReworks = entity.getReviewMaxReworks() == null
                ? 2
                : Math.max(0, Math.min(5, entity.getReviewMaxReworks()));
        Instant updatedAt = entity.getUpdatedAt() == null
                ? null
                : entity.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant();
        return new CurrentAgentSettings(enabled, maxReworks, updatedAt);
    }

    public record CurrentAgentSettings(
            boolean reviewEnabled,
            int reviewMaxReworks,
            Instant updatedAt
    ) {
    }
}
