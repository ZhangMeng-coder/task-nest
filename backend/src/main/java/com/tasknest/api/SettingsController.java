package com.tasknest.api;

import com.tasknest.api.dto.AgentSettingsView;
import com.tasknest.api.dto.LlmSettingsView;
import com.tasknest.api.dto.UpdateAgentSettingsRequest;
import com.tasknest.api.dto.UpdateLlmSettingsRequest;
import com.tasknest.common.ApiResponse;
import com.tasknest.service.AgentSettingsService;
import com.tasknest.service.LlmSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行时设置 REST 入口：LLM 连接与 Agent 审核。
 *
 * <p>设置持久化在 MySQL 单例表（{@code tn_llm_setting} / {@code tn_llm_setting_model} /
 * {@code tn_agent_setting}），保存后立即热更新服务内运行时快照，无需重启。
 * 读取 LLM 设置时 literal 密钥只返回掩码；日志与响应均不得输出明文密钥。</p>
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final LlmSettingsService llmSettingsService;
    private final AgentSettingsService agentSettingsService;

    public SettingsController(LlmSettingsService llmSettingsService,
                              AgentSettingsService agentSettingsService) {
        this.llmSettingsService = llmSettingsService;
        this.agentSettingsService = agentSettingsService;
    }

    /**
     * {@code GET /api/settings/llm}：读取当前 LLM 连接设置。
     *
     * <p>{@code LITERAL} 模式只返回密钥掩码与 {@code apiKeyConfigured}；
     * {@code ENV} 模式返回环境变量名，并通过 {@code envResolved} 表示当前能否解析到值。</p>
     */
    @GetMapping("/llm")
    public ApiResponse<LlmSettingsView> getLlmSettings() {
        return ApiResponse.ok(llmSettingsService.view());
    }

    /**
     * {@code PUT /api/settings/llm}：保存 LLM 连接设置并立即替换运行时配置。
     *
     * <p>{@code baseUrl}、{@code modelName}、{@code apiKeyType} 必填。literal 模式下已有
     * 密钥时留空表示沿用已保存值，否则必须填写；ENV 模式下它表示环境变量名。
     * 响应只返回掩码，不返回明文。</p>
     */
    @PutMapping("/llm")
    public ApiResponse<LlmSettingsView> updateLlmSettings(
            @Valid @RequestBody UpdateLlmSettingsRequest request) {
        return ApiResponse.ok(llmSettingsService.update(request));
    }

    /**
     * {@code GET /api/settings/agent}：读取审核开关与最大重做次数。
     *
     * <p>默认开启审核、最多重做 2 次；返回值同时包含最后更新时间。</p>
     */
    @GetMapping("/agent")
    public ApiResponse<AgentSettingsView> getAgentSettings() {
        return ApiResponse.ok(agentSettingsService.view());
    }

    /**
     * {@code PUT /api/settings/agent}：保存 Agent 审核设置并立即热更新。
     *
     * <p>{@code reviewEnabled} 与 {@code reviewMaxReworks} 均必填，后者范围为 0–5；
     * 越界由 Bean Validation 拒绝并返回 {@code INVALID_PARAM (40001)}。</p>
     */
    @PutMapping("/agent")
    public ApiResponse<AgentSettingsView> updateAgentSettings(
            @Valid @RequestBody UpdateAgentSettingsRequest request) {
        return ApiResponse.ok(agentSettingsService.update(request));
    }
}
