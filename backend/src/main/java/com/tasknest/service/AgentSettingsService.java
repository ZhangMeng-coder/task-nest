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

/**
 * 审核 Agent 的全局设置（单例表 {@code tn_agent_setting}，{@code id=1}）。
 *
 * <p>启动时通过 {@link PostConstruct} 从 MySQL 加载，读取失败回退默认值
 * （开启审核、最大重做 2 次）。保存走事务写库，成功后替换 volatile 运行时快照，
 * 使编排器无需重启即可应用新设置。设置只控制审核开关与重做上限，不包含密钥。</p>
 */
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

    /**
     * 启动加载单例设置。记录缺失或读取异常时保留构造器中的默认快照，
     * 不影响应用启动。
     */
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

    /**
     * 读取当前运行时快照的只读视图（不含内部实体）。
     */
    public AgentSettingsView view() {
        CurrentAgentSettings snapshot = current;
        return new AgentSettingsView(
                snapshot.reviewEnabled(), snapshot.reviewMaxReworks(), snapshot.updatedAt());
    }

    /**
     * 校验并保存审核设置，随后立即替换运行时快照。快照替换与写库在同一个
     * 事务方法内，发生在事务提交之前。
     *
     * @throws BusinessException 字段缺失或 {@code reviewMaxReworks} 超出 0–5 时抛出
     *                           {@link ErrorCode#INVALID_PARAM}
     */
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

    /**
     * 供编排器 / 审核流程读取的 volatile 运行时快照，调用方无需加锁。
     */
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

    /**
     * 运行时快照。
     *
     * @param reviewEnabled   是否启用独立审核 Agent
     * @param reviewMaxReworks 单个子任务允许的最大重做次数（0–5）
     * @param updatedAt       最近一次保存时间；来自默认值时为 {@code null}
     */
    public record CurrentAgentSettings(
            boolean reviewEnabled,
            int reviewMaxReworks,
            Instant updatedAt
    ) {
    }
}
