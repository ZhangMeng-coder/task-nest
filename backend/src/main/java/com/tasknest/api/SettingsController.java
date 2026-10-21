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

    @GetMapping("/llm")
    public ApiResponse<LlmSettingsView> getLlmSettings() {
        return ApiResponse.ok(llmSettingsService.view());
    }

    /** 保存后立即替换运行时配置；literal 密钥不会在响应中返回明文。 */
    @PutMapping("/llm")
    public ApiResponse<LlmSettingsView> updateLlmSettings(
            @Valid @RequestBody UpdateLlmSettingsRequest request) {
        return ApiResponse.ok(llmSettingsService.update(request));
    }

    @GetMapping("/agent")
    public ApiResponse<AgentSettingsView> getAgentSettings() {
        return ApiResponse.ok(agentSettingsService.view());
    }

    @PutMapping("/agent")
    public ApiResponse<AgentSettingsView> updateAgentSettings(
            @Valid @RequestBody UpdateAgentSettingsRequest request) {
        return ApiResponse.ok(agentSettingsService.update(request));
    }
}
