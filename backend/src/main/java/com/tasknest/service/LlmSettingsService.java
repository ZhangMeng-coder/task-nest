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
 * OpenAI 兼容 LLM 的连接设置（单例表 {@code tn_llm_setting} 与
 * {@code tn_llm_setting_model}，{@code id=1}）。
 *
 * <p>启动时从 MySQL 自动加载，保存后立即替换 volatile 运行时快照，供
 * {@code LlmClient} 发起真实请求、{@link SecretProvider} 收集脱敏值使用。
 * LITERAL 模式的密钥读取接口只返回掩码；ENV 模式只保存变量名，
 * 并在实际取用时解析环境变量。日志不得输出密钥本身。</p>
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

    /**
     * 启动加载两张单例表。任一张存在即合并读取；都缺失时使用默认 OpenAI
     * 兼容配置（ENV 模式、{@code OPENAI_API_KEY}）。读取异常回退默认快照，
     * 不阻止应用启动。
     */
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

    /**
     * 构造对外视图。LITERAL 密钥只返回尾 4 位掩码（长度不超过 4 时返回
     * {@code ****}）；ENV 模式返回变量名而非解析后的值。
     */
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

    /**
     * 校验并保存连接设置（两张表在同一事务内），写完两张表后替换运行时快照；
     * 快照替换发生在事务提交前，提交失败时内存与库可能短暂不一致。
     * LITERAL 模式提交空白密钥时，若库中已有 literal 密钥则沿用旧值，
     * 便于前端以掩码展示且留空不覆盖。
     *
     * @throws BusinessException Base URL / 模型名 / 环境变量名非法，或缺少密钥时抛出
     *                           {@link ErrorCode#INVALID_PARAM}
     */
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
     * 返回当前运行时快照。ENV 模式下 {@code apiKeyValue} 仍是变量名，
     * 需要实际密钥时调用 {@link #resolveApiKey()}。
     */
    public CurrentLlmSettings current() {
        return current;
    }

    /**
     * 解析可直接用于请求的密钥。ENV 模式每次调用都重新读取环境变量，
     * 因此修改变量值无需重启；变量不存在时返回 {@code null}。
     */
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

    /**
     * 只暴露尾 4 位，避免把完整密钥返回给前端或写入日志。
     */
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
