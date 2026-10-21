package com.tasknest.service;

import com.tasknest.api.dto.LlmSettingsView;
import com.tasknest.api.dto.UpdateLlmSettingsRequest;
import com.tasknest.common.BusinessException;
import com.tasknest.common.ErrorCode;
import com.tasknest.persistence.entity.LlmSettingEntity;
import com.tasknest.persistence.entity.LlmSettingModelEntity;
import com.tasknest.persistence.service.LlmSettingEntityService;
import com.tasknest.persistence.service.LlmSettingModelEntityService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * LLM 连接设置：启动时从 MySQL 自动加载，保存后立即替换内存中的运行时快照。
 * 当前阶段只提供配置，不会发起真实模型请求。
 */
@Service
public class LlmSettingsService {

    private static final Logger log = LoggerFactory.getLogger(LlmSettingsService.class);
    private static final long SETTING_ID = 1L;
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL_NAME = "gpt-4o";
    private static final String DEFAULT_ENV_NAME = "OPENAI_API_KEY";
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final LlmSettingEntityService settingService;
    private final LlmSettingModelEntityService modelSettingService;
    private volatile CurrentLlmSettings current = new CurrentLlmSettings(
            DEFAULT_BASE_URL, DEFAULT_MODEL_NAME, ApiKeyType.ENV, DEFAULT_ENV_NAME, null);

    public LlmSettingsService(
            LlmSettingEntityService settingService,
            LlmSettingModelEntityService modelSettingService) {
        this.settingService = settingService;
        this.modelSettingService = modelSettingService;
    }

    @PostConstruct
    public void load() {
        try {
            LlmSettingEntity entity = settingService.getById(SETTING_ID);
            LlmSettingModelEntity modelEntity = modelSettingService.getById(SETTING_ID);
            if (entity == null && modelEntity == null) {
                current = new CurrentLlmSettings(
                        DEFAULT_BASE_URL, DEFAULT_MODEL_NAME, ApiKeyType.ENV,
                        DEFAULT_ENV_NAME, null);
                log.info("LLM 设置尚未保存，使用默认 OpenAI 兼容配置");
                return;
            }
            current = fromEntities(entity, modelEntity);
            log.info("已从 MySQL 加载 LLM 设置: baseUrl={}, modelName={}, apiKeyType={}",
                    current.baseUrl(), current.modelName(), current.apiKeyType());
        } catch (RuntimeException exception) {
            log.warn("加载 LLM 设置失败，继续使用默认配置", exception);
        }
    }

    public LlmSettingsView view() {
        CurrentLlmSettings snapshot = current;
        boolean literal = snapshot.apiKeyType() == ApiKeyType.LITERAL;
        boolean configured = hasText(snapshot.apiKeyValue());
        boolean envResolved = snapshot.apiKeyType() == ApiKeyType.ENV
                && configured
                && hasText(System.getenv(snapshot.apiKeyValue()));
        return new LlmSettingsView(
                snapshot.baseUrl(),
                snapshot.modelName(),
                snapshot.apiKeyType(),
                snapshot.apiKeyType() == ApiKeyType.ENV ? snapshot.apiKeyValue() : null,
                literal ? mask(snapshot.apiKeyValue()) : null,
                configured,
                envResolved,
                snapshot.updatedAt());
    }

    @Transactional
    public LlmSettingsView update(UpdateLlmSettingsRequest request) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String modelName = normalizeModelName(request.modelName());
        ApiKeyType type = request.apiKeyType();
        LlmSettingEntity existing = settingService.getById(SETTING_ID);
        String nextValue = nextApiKeyValue(type, request.apiKeyValue(), existing);

        LocalDateTime now = LocalDateTime.now();
        LlmSettingEntity entity = existing == null ? new LlmSettingEntity() : existing;
        entity.setId(SETTING_ID);
        entity.setBaseUrl(baseUrl);
        entity.setApiKeyType(type.name());
        entity.setApiKeyValue(nextValue);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        settingService.saveOrUpdate(entity);

        LlmSettingModelEntity modelEntity = modelSettingService.getById(SETTING_ID);
        if (modelEntity == null) {
            modelEntity = new LlmSettingModelEntity();
            modelEntity.setId(SETTING_ID);
            modelEntity.setCreatedAt(now);
        }
        modelEntity.setModelName(modelName);
        modelEntity.setUpdatedAt(now);
        modelSettingService.saveOrUpdate(modelEntity);

        current = new CurrentLlmSettings(
                baseUrl, modelName, type, nextValue, toInstant(now));
        log.info("LLM 设置已更新并立即生效: baseUrl={}, modelName={}, apiKeyType={}",
                baseUrl, modelName, type);
        return view();
    }

    /**
     * 供后续 P3 编排器读取当前配置；ENV 模式每次调用都会重新解析环境变量。
     */
    public CurrentLlmSettings current() {
        return current;
    }

    public String resolveApiKey() {
        CurrentLlmSettings snapshot = current;
        if (snapshot.apiKeyType() == ApiKeyType.ENV) {
            return hasText(snapshot.apiKeyValue()) ? System.getenv(snapshot.apiKeyValue()) : null;
        }
        return snapshot.apiKeyValue();
    }

    private String nextApiKeyValue(ApiKeyType type, String rawValue, LlmSettingEntity existing) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (type == ApiKeyType.ENV) {
            if (value.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "环境变量名不能为空");
            }
            if (!ENV_NAME.matcher(value).matches()) {
                throw new BusinessException(ErrorCode.INVALID_PARAM,
                        "环境变量名只能包含字母、数字和下划线，且不能以数字开头");
            }
            return value;
        }
        if (!value.isBlank()) {
            return value;
        }
        ApiKeyType existingType = existing == null ? null : parseType(existing.getApiKeyType());
        if (existingType == ApiKeyType.LITERAL && hasText(existing.getApiKeyValue())) {
            return existing.getApiKeyValue();
        }
        throw new BusinessException(ErrorCode.INVALID_PARAM, "请填写 API Key，或切换到环境变量模式");
    }

    private String normalizeBaseUrl(String rawBaseUrl) {
        String value = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        if (value.length() > 500) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "baseUrl 最多 500 字");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("unsupported url");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "请求地址必须是完整的 http/https OpenAI 兼容地址，例如 https://api.openai.com/v1");
        }
        return value;
    }

    private String normalizeModelName(String rawModelName) {
        String value = rawModelName == null ? "" : rawModelName.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "模型名称不能为空，例如 gpt-4o");
        }
        if (value.length() > 128) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "模型名称最多 128 字");
        }
        return value;
    }

    private CurrentLlmSettings fromEntities(
            LlmSettingEntity entity, LlmSettingModelEntity modelEntity) {
        ApiKeyType type = entity == null ? ApiKeyType.ENV : parseType(entity.getApiKeyType());
        String baseUrl = entity != null && hasText(entity.getBaseUrl())
                ? entity.getBaseUrl()
                : DEFAULT_BASE_URL;
        String apiKeyValue = entity == null ? DEFAULT_ENV_NAME : entity.getApiKeyValue();
        String modelName = modelEntity != null && hasText(modelEntity.getModelName())
                ? modelEntity.getModelName()
                : DEFAULT_MODEL_NAME;
        LocalDateTime updatedAt = entity != null && entity.getUpdatedAt() != null
                ? entity.getUpdatedAt()
                : (modelEntity == null ? null : modelEntity.getUpdatedAt());
        return new CurrentLlmSettings(
                baseUrl, modelName, type, apiKeyValue, toInstant(updatedAt));
    }

    private ApiKeyType parseType(String value) {
        try {
            return ApiKeyType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.warn("无法识别的 apiKeyType: {}，回退为 ENV", value);
            return ApiKeyType.ENV;
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private String mask(String value) {
        if (!hasText(value)) {
            return null;
        }
        if (value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 当前运行时配置快照。apiKeyValue 对 LITERAL 是密钥本身，对 ENV 是变量名。
     */
    public record CurrentLlmSettings(
            String baseUrl,
            String modelName,
            ApiKeyType apiKeyType,
            String apiKeyValue,
            Instant updatedAt
    ) {
    }
}
